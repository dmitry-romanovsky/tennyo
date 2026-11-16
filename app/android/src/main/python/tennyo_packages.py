"""Resolves a workspace's requirements to wheels and installs them, with every failure answered as a typed outcome.

resolve() downloads and verifies wheels into a staging folder and never makes anything importable; install() unpacks
the staged wheels into a fresh folder and swaps it in atomically. Only https hosts in HOSTS are ever contacted.
"""
import collections
import email.parser
import hashlib
import html.parser
import io
import json
import os
import platform
import shutil
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

import packaging
from packaging.requirements import InvalidRequirement, Requirement
from packaging.specifiers import SpecifierSet
from packaging.tags import sys_tags
from packaging.utils import InvalidWheelFilename, canonicalize_name, parse_wheel_filename
from packaging.version import Version

HOSTS = {"pypi.org", "files.pythonhosted.org", "chaquo.com"}
CHAQUOPY = "https://chaquo.com/pypi-13.1/"
PYPI = "https://pypi.org/simple/"
PYPI_ACCEPT = "application/vnd.pypi.simple.v1+json"
CONSENT_RECORD = "tennyo-packages.json"
SOCKET_TIMEOUT = 20
DEADLINE_SECONDS = 300
INDEX_LIMIT = 20 << 20
WHEEL_LIMIT = 50 << 20
DOWNLOAD_LIMIT = 200 << 20
MAX_DISTRIBUTIONS = 50
MAX_DEPTH = 10
MAX_BACKTRACKS = 20
MAX_ENTRIES = 10_000
MAX_UNPACKED = 500 << 20
MAX_RATIO = 100
RESERVED_TOP_LEVEL = set(sys.stdlib_module_names) | {
    "java", "chaquopy", "android", "com", "org", "tennyo_runner", "tennyo_packages", "packaging", "pip",
    "setuptools", "sitecustomize", "usercustomize",
}
# Shipped in the APK, so a dependency on them is met without a download.
PROVIDED = {"packaging": Version(packaging.__version__)}
# Lower is a better match. Replaced by the self-check.
TAGS = {tag: rank for rank, tag in enumerate(sys_tags())}


class Failure(Exception):
    def __init__(self, outcome, detail):
        super().__init__(outcome, detail)
        self.outcome, self.detail = outcome, detail


_Chosen = collections.namedtuple("_Chosen", "version index requires")
_File = collections.namedtuple("_File", "filename url sha256 requires_python")


def resolve(requirements, staging_dir, opener=None):
    """Answers [outcome, detail, [[name, version, index], ...]]; index is "chaquopy" or "pypi"."""
    try:
        parsed = [_parse(line) for line in requirements]
        shutil.rmtree(staging_dir, ignore_errors=True)
        os.makedirs(staging_dir)
        chosen = _resolve(parsed, staging_dir, _Fetcher(opener or _default_opener()))
    except Failure as failure:
        return [failure.outcome, failure.detail, []]
    except Exception as error:  # a malformed index or a full disk must still answer, never hang the caller
        return ["rejected", type(error).__name__, []]
    return ["resolved", "", [[name, str(c.version), c.index] for name, c in chosen.items()]]


def install(staging_dir, target_dir):
    """Answers [outcome, detail]; the old target survives every failure."""
    new, old = target_dir + ".new", target_dir + ".old"
    shutil.rmtree(new, ignore_errors=True)
    try:
        wheels = sorted(os.path.join(staging_dir, f) for f in os.listdir(staging_dir) if f.endswith(".whl"))
        os.makedirs(new)
        _unpack(wheels, new)
        record = os.path.join(staging_dir, CONSENT_RECORD)
        if os.path.isfile(record) and not os.path.islink(record):
            shutil.copyfile(record, os.path.join(new, CONSENT_RECORD))
        shutil.rmtree(old, ignore_errors=True)
        if os.path.isdir(target_dir):
            os.rename(target_dir, old)
        try:
            os.rename(new, target_dir)
        except OSError:
            if os.path.isdir(old):
                os.rename(old, target_dir)
            raise
        shutil.rmtree(old, ignore_errors=True)
    except Failure as failure:
        shutil.rmtree(new, ignore_errors=True)
        return [failure.outcome, failure.detail]
    except Exception as error:  # a bad archive raises many types: BadZipFile, zlib.error, NotImplementedError...
        shutil.rmtree(new, ignore_errors=True)
        return ["rejected", type(error).__name__]
    return ["installed", ""]


def _parse(line):
    try:
        requirement = Requirement(line)
    except InvalidRequirement:
        raise Failure("invalid_requirement", line) from None
    if requirement.url:
        raise Failure("invalid_requirement", line)
    return requirement


class _Conflict(Exception):
    """A later constraint excludes the version already chosen for name."""

    def __init__(self, name, version):
        super().__init__(name, version)
        self.name, self.version = name, version


def _resolve(requirements, staging_dir, fetcher):
    # On a conflict the whole walk restarts with the excluded version banned, so the next-best wheel is tried and
    # its dependencies re-derived. Bans are never lifted, so a version that only conflicted under an abandoned
    # choice stays out, and MAX_BACKTRACKS caps the restarts. A requirement set that fails with no_compatible_wheel
    # although a solution exists needs a real backtracking resolver such as resolvelib.
    banned = set()
    for _ in range(MAX_BACKTRACKS):
        try:
            return _walk(requirements, staging_dir, fetcher, banned)
        except _Conflict as conflict:
            banned.add((conflict.name, conflict.version))
    try:
        return _walk(requirements, staging_dir, fetcher, banned)
    except _Conflict as conflict:
        raise Failure("no_compatible_wheel", conflict.name) from None


def _walk(requirements, staging_dir, fetcher, banned):
    # An abandoned walk's wheels must not be installed, and its downloads must not eat the next walk's budget.
    for name in os.listdir(staging_dir):
        if name.endswith(".whl"):
            os.remove(os.path.join(staging_dir, name))
    fetcher.downloaded = 0
    specs, chosen, expanded = {}, {}, set()
    queue = collections.deque((requirement, 0, "") for requirement in requirements)
    while queue:
        requirement, depth, extra = queue.popleft()
        if requirement.marker and not requirement.marker.evaluate({"extra": extra}):
            continue
        name = canonicalize_name(requirement.name)
        specs[name] = specs.get(name, SpecifierSet()) & requirement.specifier
        if name in PROVIDED:
            if not specs[name].contains(PROVIDED[name], prereleases=True):
                raise Failure("no_compatible_wheel", name)
            continue
        if name not in chosen:
            if len(chosen) >= MAX_DISTRIBUTIONS:
                raise Failure("rejected", name)
            chosen[name] = _download(fetcher, name, specs[name], staging_dir, len(chosen), banned)
        elif not specs[name].contains(chosen[name].version, prereleases=True):
            raise _Conflict(name, chosen[name].version)
        for requested in {""} | set(requirement.extras):
            if (name, requested) in expanded:
                continue
            if depth >= MAX_DEPTH:
                raise Failure("rejected", name)
            expanded.add((name, requested))
            queue.extend((dep, depth + 1, requested) for dep in chosen[name].requires)
    return chosen


def _download(fetcher, name, specifier, staging_dir, number, banned):
    index, files = _list_files(fetcher, name)
    python = Version("%d.%d.%d" % sys.version_info[:3])
    best = None
    for file in files:
        try:
            wheel_name, version, build, tags = parse_wheel_filename(file.filename)
        except InvalidWheelFilename:
            continue
        ranks = [TAGS[tag] for tag in tags if tag in TAGS]
        if wheel_name != name or not ranks or not specifier.contains(version, prereleases=True):
            continue
        if (name, version) in banned:
            continue
        if file.requires_python and not SpecifierSet(file.requires_python).contains(python, prereleases=True):
            continue
        key = (not version.is_prerelease, version, -min(ranks), build)
        if best is None or key > best[0]:
            best = (key, file)
    if best is None:
        raise Failure("no_compatible_wheel", name)
    version, file = best[0][1], best[1]
    data = fetcher.get(file.url, WHEEL_LIMIT)
    if data is None:
        raise Failure("not_found", name)
    # The Chaquopy index publishes no hashes, so its files rest on TLS alone.
    if file.sha256 and hashlib.sha256(data).hexdigest() != file.sha256.lower():
        raise Failure("rejected", name)
    with open(os.path.join(staging_dir, "%d.whl" % number), "wb") as out:
        out.write(data)
    return _Chosen(version, index, _requires(data, name))


def _requires(wheel, name):
    try:
        with zipfile.ZipFile(io.BytesIO(wheel)) as archive:
            metadata = next(
                n for n in archive.namelist() if n.endswith(".dist-info/METADATA") and n.count("/") == 1
            )
            with archive.open(metadata) as member:
                text = member.read(1 << 20).decode("utf-8", "replace")
        return [Requirement(line) for line in email.parser.Parser().parsestr(text).get_all("Requires-Dist", [])]
    except (zipfile.BadZipFile, StopIteration, InvalidRequirement):
        raise Failure("rejected", name) from None


def _list_files(fetcher, name):
    """The project's files from Chaquopy when it lists the project, otherwise from PyPI."""
    page_url = CHAQUOPY + name + "/"
    page = fetcher.get(page_url, INDEX_LIMIT)
    try:
        if page is not None:
            return "chaquopy", _chaquopy_files(page_url, page)
        page = fetcher.get(PYPI + name + "/", INDEX_LIMIT, PYPI_ACCEPT)
        if page is None:
            raise Failure("not_found", name)
        return "pypi", _pypi_files(page)
    except (ValueError, LookupError, TypeError, AttributeError):
        raise Failure("network", name) from None


def _chaquopy_files(page_url, page):
    links = _Links()
    links.feed(page.decode("utf-8", "replace"))
    files = []
    for href in links.hrefs:
        url = urllib.parse.urldefrag(urllib.parse.urljoin(page_url, href))[0]
        filename = urllib.parse.unquote(urllib.parse.urlsplit(url).path.rpartition("/")[2])
        if filename.endswith(".whl"):
            files.append(_File(filename, _checked(url), None, None))
    return files


def _pypi_files(page):
    files = []
    for entry in json.loads(page)["files"]:
        sha256 = entry.get("hashes", {}).get("sha256")
        if entry["filename"].endswith(".whl") and not entry.get("yanked") and sha256:
            files.append(_File(entry["filename"], _checked(entry["url"]), sha256, entry.get("requires-python")))
    return files


class _Links(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.hrefs = []

    def handle_starttag(self, tag, attrs):
        href = dict(attrs).get("href")
        if tag == "a" and href:
            self.hrefs.append(href)


def _checked(url):
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "https" or parts.hostname not in HOSTS:
        raise Failure("rejected", url)
    return url


class _Redirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return super().redirect_request(req, fp, code, msg, headers, _checked(newurl))


def _default_opener():
    return urllib.request.build_opener(_Redirects)


class _Fetcher:
    def __init__(self, opener):
        self.opener = opener
        self.deadline = time.monotonic() + DEADLINE_SECONDS
        self.downloaded = 0

    def get(self, url, limit, accept=None):
        """The body at url, or None for HTTP 404. The url is checked before the opener sees it."""
        request = urllib.request.Request(_checked(url), headers={"Accept": accept} if accept else {})
        chunks, size = [], 0
        try:
            with self.opener.open(request, timeout=SOCKET_TIMEOUT) as response:
                while chunk := response.read(1 << 16):
                    size += len(chunk)
                    self.downloaded += len(chunk)
                    if size > limit or self.downloaded > DOWNLOAD_LIMIT:
                        raise Failure("rejected", url)
                    if time.monotonic() > self.deadline:
                        raise Failure("timed_out", url)
                    chunks.append(chunk)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            raise Failure("network", url) from error
        except OSError as error:
            timed_out = isinstance(error, TimeoutError) or isinstance(getattr(error, "reason", None), TimeoutError)
            raise Failure("timed_out" if timed_out else "network", url) from error
        return b"".join(chunks)


def _unpack(wheels, target):
    """Validates every wheel completely, then extracts; nothing is written before validation passes."""
    deadline = time.monotonic() + DEADLINE_SECONDS
    seen, plan, unpacked = set(), [], 0
    archives = [zipfile.ZipFile(path) for path in wheels]
    try:
        for archive in archives:
            infos = archive.infolist()
            if len(infos) > MAX_ENTRIES:
                raise Failure("rejected", "entries")
            for info in infos:
                parts = _destination(info)
                if parts is None:
                    continue
                if info.file_size > MAX_RATIO * max(info.compress_size, 1) and info.file_size > 1 << 20:
                    raise Failure("rejected", info.filename)
                unpacked += info.file_size
                if unpacked > MAX_UNPACKED:
                    raise Failure("rejected", "size")
                if not info.is_dir():
                    if tuple(parts) in seen:
                        raise Failure("rejected", info.filename)
                    seen.add(tuple(parts))
                plan.append((archive, info, parts))
        if shutil.disk_usage(target).free < 2 * unpacked:
            raise Failure("rejected", "disk")
        for archive, info, parts in plan:
            if time.monotonic() > deadline:
                raise Failure("timed_out", "unpack")
            path = os.path.join(target, *parts)
            if info.is_dir():
                os.makedirs(path, exist_ok=True)
                continue
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with archive.open(info) as source, open(path, "wb") as out:
                shutil.copyfileobj(source, out)
    finally:
        for archive in archives:
            archive.close()


def _destination(info):
    """Path parts for a member, None for one that is dropped; raises Failure for an unsafe one."""
    name = info.orig_filename  # zipfile rewrites os.sep and cuts at NUL in filename, hiding both
    kind = stat.S_IFMT(info.external_attr >> 16)
    parts = name.split("/")
    if (
        "\0" in name or "\\" in name or name.startswith("/") or name[1:2] == ":" or ".." in parts
        or kind not in (0, stat.S_IFREG, stat.S_IFDIR)
    ):
        raise Failure("rejected", name)
    parts = [part for part in parts if part]
    if parts and parts[0].endswith(".data"):
        if len(parts) < 3 or parts[1] not in ("purelib", "platlib"):
            return None
        parts = parts[2:]
    if not parts or parts[-1].endswith(".pth"):
        return None
    if parts[0].split(".")[0] in RESERVED_TOP_LEVEL:
        raise Failure("rejected", name)
    return parts


def _self_check():
    import tempfile
    from email.message import Message

    from packaging.tags import Tag

    global TAGS
    pure, native = Tag("py3", "none", "any"), Tag("cp313", "cp313", "android_24_x86_64")
    TAGS = {native: 0, pure: 1}
    files_host = "https://files.pythonhosted.org/packages/"

    def sha(data):
        return hashlib.sha256(data).hexdigest()

    def member(name, mode=0o100644):
        info = zipfile.ZipInfo("x", date_time=(2020, 1, 1, 0, 0, 0))
        info.filename = name  # assigned after construction: ZipInfo would rewrite os.sep itself
        info.external_attr = mode << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        return info

    def wheel(name, version, requires=(), extra=()):
        buffer = io.BytesIO()
        metadata = "Metadata-Version: 2.1\nName: %s\nVersion: %s\n" % (name, version)
        metadata += "".join("Requires-Dist: %s\n" % line for line in requires)
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr(member("%s-%s.dist-info/METADATA" % (name, version)), metadata)
            archive.writestr(member("%s/__init__.py" % name), "")
            for info, data in extra:
                archive.writestr(info, data)
        return buffer.getvalue()

    class Net:
        """Offline HTTPS: url -> bytes, ("redirect", url) or an exception; records every request."""

        def __init__(self):
            self.pages, self.requested = {}, []
            net = self

            class Scripted(urllib.request.HTTPSHandler):
                def https_open(self, request):
                    url = request.full_url
                    net.requested.append(url)
                    page = net.pages.get(url)
                    if isinstance(page, Exception):
                        raise page
                    if page is None:
                        raise urllib.error.HTTPError(url, 404, "Not Found", Message(), None)
                    headers = Message()
                    code = 200
                    if isinstance(page, tuple):
                        code, headers["Location"], page = 302, page[1], b""
                    reply = urllib.response.addinfourl(io.BytesIO(page), headers, url, code)
                    reply.msg = "Found" if code == 302 else "OK"
                    return reply

            self.opener = urllib.request.build_opener(_Redirects, Scripted)

        def pypi(self, name, wheels, **override):
            entries = [
                {"filename": f, "url": files_host + f, "hashes": {"sha256": sha(d)}, **override}
                for f, d in wheels.items()
            ]
            self.pages[PYPI + name + "/"] = json.dumps({"files": entries}).encode()
            self.pages.update({files_host + f: d for f, d in wheels.items()})

        def chaquopy(self, name, wheels):
            base = CHAQUOPY + name + "/"
            links = '<a href="?C=N;O=D">Name</a>' + "".join('<a href="%s">%s</a>' % (f, f) for f in wheels)
            self.pages[base] = ("<html><body>%s</body></html>" % links).encode()
            self.pages.update({base + f: d for f, d in wheels.items()})

    def resolved(net, requirements, staging):
        return resolve(requirements, staging, net.opener)

    with tempfile.TemporaryDirectory() as base:
        staging = os.path.join(base, "staging")

        net = Net()
        net.pypi("app", {"app-1.0-py3-none-any.whl": wheel("app", "1.0", ["lib>=1"])})
        net.pypi("lib", {
            "lib-2.0-py3-none-any.whl": wheel("lib", "2.0", ["app"]),
            "lib-1.5-py3-none-any.whl": wheel("lib", "1.5"),
        })
        assert resolved(net, ["App"], staging) == ["resolved", "", [["app", "1.0", "pypi"], ["lib", "2.0", "pypi"]]]
        target = os.path.join(base, "target")
        os.makedirs(target)
        with open(os.path.join(target, "old.txt"), "w") as file:
            file.write("old")
        with open(os.path.join(staging, CONSENT_RECORD), "w") as file:
            file.write("{}")
        assert install(staging, target) == ["installed", ""]
        assert os.path.isfile(os.path.join(target, "app", "__init__.py"))
        assert os.path.isfile(os.path.join(target, "lib", "__init__.py"))
        assert os.path.isfile(os.path.join(target, CONSENT_RECORD))
        assert not os.path.exists(os.path.join(target, "old.txt"))
        assert not os.path.exists(target + ".new") and not os.path.exists(target + ".old")

        assert resolved(net, ["ghost"], staging) == ["not_found", "ghost", []]
        net.pypi("fresh", {
            "fresh-2.0rc1-py3-none-any.whl": wheel("fresh", "2.0rc1"),
            "fresh-1.9-py3-none-any.whl": wheel("fresh", "1.9"),
        })
        assert resolved(net, ["fresh"], staging)[2] == [["fresh", "1.9", "pypi"]]
        net.pypi("odd", {"odd-1.0-py3-none-any.whl": wheel("odd", "1.0")}, **{"requires-python": "not a spec"})
        assert resolved(net, ["odd"], staging) == ["rejected", "InvalidSpecifier", []]
        net.pypi("uses-packaging", {"uses_packaging-1.0-py3-none-any.whl": wheel("uses_packaging", "1.0", ["packaging>=20"])})
        assert resolved(net, ["uses-packaging"], staging) == ["resolved", "", [["uses-packaging", "1.0", "pypi"]]]
        assert PYPI + "packaging/" not in net.requested
        net.pages[PYPI + "down/"] = urllib.error.URLError("offline")
        assert resolved(net, ["down"], staging)[0] == "network"
        assert resolved(net, ["name @ https://x/y.whl"], staging)[:2] == ["invalid_requirement", "name @ https://x/y.whl"]

        net = Net()
        net.pypi("top", {"top-1.0-py3-none-any.whl": wheel("top", "1.0", ["wide", "narrow"])})
        net.pypi("wide", {
            "wide-2.0-py3-none-any.whl": wheel("wide", "2.0", ["extra"]),
            "wide-1.0-py3-none-any.whl": wheel("wide", "1.0"),
        })
        net.pypi("extra", {"extra-1.0-py3-none-any.whl": wheel("extra", "1.0")})
        net.pypi("narrow", {"narrow-1.0-py3-none-any.whl": wheel("narrow", "1.0", ["wide<2"])})
        # The abandoned wide 2.0 pulled in extra; its wheel must not survive in staging.
        assert resolved(net, ["top"], staging) == [
            "resolved", "", [["top", "1.0", "pypi"], ["wide", "1.0", "pypi"], ["narrow", "1.0", "pypi"]],
        ]
        assert sorted(os.listdir(staging)) == ["0.whl", "1.whl", "2.whl"]
        net.pypi("narrow", {"narrow-1.0-py3-none-any.whl": wheel("narrow", "1.0", ["wide<1"])})
        assert resolved(net, ["top"], staging) == ["no_compatible_wheel", "wide", []]
        net.pypi("narrow", {"narrow-1.0-py3-none-any.whl": wheel("narrow", "1.0", ["wide<2"])})
        global MAX_BACKTRACKS
        limit, MAX_BACKTRACKS = MAX_BACKTRACKS, 0
        assert resolved(net, ["top"], staging) == ["no_compatible_wheel", "wide", []]
        MAX_BACKTRACKS = limit

        net = Net()
        net.chaquopy("pyyaml", {"pyyaml-6.0.3-0-cp313-cp313-android_24_x86_64.whl": wheel("pyyaml", "6.0.3")})
        net.pypi("pyyaml", {"pyyaml-99.0-py3-none-any.whl": wheel("pyyaml", "99.0")})
        assert resolved(net, ["pyyaml"], staging) == ["resolved", "", [["pyyaml", "6.0.3", "chaquopy"]]]

        net = Net()
        net.pages[PYPI + "redir/"] = ("redirect", "https://evil.example/x")
        assert resolved(net, ["redir"], staging)[0] == "rejected"
        assert "https://evil.example/x" not in net.requested
        for bad in ("http://files.pythonhosted.org/x.whl", "file:///etc/passwd", "https://evil.example/x.whl"):
            net = Net()
            net.pypi("plain", {"plain-1.0-py3-none-any.whl": wheel("plain", "1.0")}, url=bad)
            assert resolved(net, ["plain"], staging)[0] == "rejected" and bad not in net.requested
        net = Net()
        net.pypi("plain", {"plain-1.0-py3-none-any.whl": wheel("plain", "1.0")}, hashes={"sha256": "0" * 64})
        assert resolved(net, ["plain"], staging) == ["rejected", "plain", []]

        def staged(*wheels):
            shutil.rmtree(staging, ignore_errors=True)
            os.makedirs(staging)
            for number, data in enumerate(wheels):
                with open(os.path.join(staging, "%d.whl" % number), "wb") as file:
                    file.write(data)

        hostile = {
            "parent": [(member("../escape"), b"x")],
            "absolute": [(member("/abs"), b"x")],
            "backslash": [(member("a\\b"), b"x")],
            "drive": [(member("C:x"), b"x")],
            "nul": [(member("a\0b"), b"x")],
            "symlink": [(member("link", stat.S_IFLNK | 0o777), b"/etc")],
            "stdlib": [(member("json/__init__.py"), b"")],
            "runner": [(member("tennyo_runner.py"), b"")],
            "bomb": [(member("big.txt"), b"\0" * 5_000_000)],
        }
        for case, extra in hostile.items():
            workspace = os.path.join(base, "iso-" + case)
            os.makedirs(os.path.join(workspace, "target"))
            marker = os.path.join(workspace, "target", "old.txt")
            with open(marker, "w") as file:
                file.write("old")
            staged(wheel("evil", "1.0", extra=extra))
            outcome = install(staging, os.path.join(workspace, "target"))
            assert outcome[0] == "rejected", (case, outcome)
            assert os.listdir(workspace) == ["target"] and os.path.isfile(marker), case
        staged(wheel("one", "1.0", extra=[(member("shared.py"), b"")]), wheel("two", "1.0", extra=[(member("shared.py"), b"")]))
        assert install(staging, os.path.join(base, "clash"))[0] == "rejected"
        assert not os.path.exists(os.path.join(base, "clash"))

        staged(wheel("good", "1.0", extra=[(member("ok.pth"), b"import os"), (member("good-1.0.data/scripts/run"), b"x")]))
        assert install(staging, target) == ["installed", ""]
        assert sorted(os.listdir(target)) == ["good", "good-1.0.dist-info"]
        assert not os.path.exists(target + ".new") and not os.path.exists(target + ".old")
    print("ok")


if __name__ == "__main__":
    _self_check()
