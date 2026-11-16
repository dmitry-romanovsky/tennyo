"""Runs a workspace's Python file as __main__ and hands back what it printed.

The file runs where it lives, with its folder and the workspace root importable and the workspace's installed packages
importable after the standard library. Every module the run imported from the workspace or from any workspace's
packages is dropped afterwards, so the next run sees fresh edits and never another workspace's packages.
"""
import contextlib
import ctypes
import importlib
import io
import os
import runpy
import sys
import threading
import traceback


def run(root, packages_dir, entry, timeout_seconds):
    """Answers [outcome, output, missing]; outcome is "succeeded", "failed" or "timed_out".

    missing is the top-level module of an uncaught ModuleNotFoundError, else "".
    """
    output = io.StringIO()
    outcome = ["succeeded"]
    missing = [""]

    def target():
        try:
            runpy.run_path(entry, run_name="__main__")
        except SystemExit as exit:
            if exit.code not in (None, 0):
                outcome[0] = "failed"
        except BaseException as error:
            if isinstance(error, ModuleNotFoundError) and error.name:
                missing[0] = error.name.split(".")[0]
            traceback.print_exc()
            outcome[0] = "failed"

    saved_path, saved_cwd = list(sys.path), os.getcwd()
    saved_modules = set(sys.modules)
    folder = os.path.dirname(entry)
    owned = (os.path.join(root, ""), os.path.join(os.path.dirname(packages_dir), ""))
    try:
        os.chdir(folder)
        importlib.invalidate_caches()
        sys.path[:0] = [folder, root]
        sys.path.append(packages_dir)
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            worker = threading.Thread(target=target, name="tennyo-run", daemon=True)
            worker.start()
            worker.join(timeout_seconds)
            if worker.is_alive():
                # A thread cannot be killed; raising in it stops any script that is still running Python code.
                ctypes.pythonapi.PyThreadState_SetAsyncExc(ctypes.c_ulong(worker.ident), ctypes.py_object(TimeoutError))
                worker.join(2)
                outcome[0] = "timed_out"
    finally:
        os.chdir(saved_cwd)
        sys.path[:] = saved_path
        for name in set(sys.modules) - saved_modules:
            module = sys.modules.get(name)
            places = [getattr(module, "__file__", None) or ""] + list(getattr(module, "__path__", None) or [])
            if any(place.startswith(owned) for place in places):
                del sys.modules[name]
    return [outcome[0], output.getvalue(), missing[0]]


def _write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as file:
        file.write(text)


if __name__ == "__main__":
    import tempfile

    with tempfile.TemporaryDirectory() as base:
        workspace, packages = os.path.join(base, "workspaces", "w"), os.path.join(base, "python-packages", "w")
        script = os.path.join(workspace, "main.py")
        _write(script, "print('hi')\nraise SystemExit(0)\n")
        assert run(workspace, packages, script, 5) == ["succeeded", "hi\n", ""]
        _write(script, "1 / 0\n")
        assert run(workspace, packages, script, 5)[::2] == ["failed", ""]
        _write(script, "import nothing_here\n")
        result = run(workspace, packages, script, 5)
        assert result[0] == "failed" and result[2] == "nothing_here"
        _write(script, "while True: pass\n")
        assert run(workspace, packages, script, 1)[0] == "timed_out"

        # Workspace "a" is a prefix of "a-b"; each sees only its own packages and modules.
        spaces = {name: os.path.join(base, "workspaces", name) for name in ("a", "a-b")}
        stores = {name: os.path.join(base, "python-packages", name) for name in ("a", "a-b")}
        _write(os.path.join(spaces["a"], "main.py"), "import helper, pkg\nprint(helper.NAME, pkg.VERSION)\n")
        _write(os.path.join(spaces["a"], "helper.py"), "NAME = 'a'\n")
        _write(os.path.join(stores["a"], "pkg.py"), "VERSION = 1\n")
        _write(
            os.path.join(spaces["a-b"], "main.py"),
            "import helper, pkg, nspkg.mod, json\nprint(helper.NAME, pkg.VERSION, nspkg.mod.X, json.dumps(1))\n",
        )
        _write(os.path.join(spaces["a-b"], "helper.py"), "NAME = 'a-b'\n")
        _write(os.path.join(spaces["a-b"], "nspkg", "mod.py"), "X = 'ns'\n")
        _write(os.path.join(stores["a-b"], "pkg.py"), "VERSION = 2\n")
        _write(os.path.join(stores["a-b"], "json.py"), "raise RuntimeError('shadowed')\n")
        for name, expected in (("a", "a 1\n"), ("a-b", "a-b 2 ns 1\n")):
            assert run(spaces[name], stores[name], os.path.join(spaces[name], "main.py"), 5) == ["succeeded", expected, ""]
            assert not {"helper", "pkg", "nspkg", "nspkg.mod"} & set(sys.modules)
    print("ok")
