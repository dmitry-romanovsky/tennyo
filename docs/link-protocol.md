# Link protocol

The link connects the phone to a **host**: the Tennyo plugin in an IntelliJ-based IDE or the extension in a VS Code
family editor. Three public HTTPS endpoints set a session up. Everything after that travels over one authenticated
WebSocket channel.

Where the code lives:

- `:shared:contract` (`shared/contract/src/commonMain/kotlin/app/tennyo/contract/`) holds the wire contract.
  `LinkProtocol.kt` declares the endpoints, frames, calls, results, pushes and fault codes; the other files declare
  the DTOs they carry.
- `:shared:crypto` holds every signed or MACed construction: `SecurityTranscripts`, `SecurityDerivations`,
  `LinkProofs`, `ProtectedPayloads`.
- `:host:engine` is the host side: `rpc/` (public endpoints and per-pane call handlers), `session/` (sessions, the
  channel, push history), `auth/` (device proofs, call MACs), `transport/` (request shape and admission) and
  `discovery/`. `:host:intellij` and `:host:vscode` only adapt it to their editor.
- `:app:link` is the phone side.

Change the contract in `:shared:contract`, never in one consumer, and update this document in the same change.

## Model

- A **session** belongs to one IDE project and is started from the IDE. Its ticket (endpoint, session id, token, TLS
  fingerprint) reaches the phone encrypted under a key agreed during pairing. It is never shown as a link, a QR code or
  anything else the user copies.
- A session approves one **device**: the user either taps the pairing words or resumes a remembered pair.
- A session has one **channel** at a time. Opening a second one replaces the first.
- The phone sends **calls**; the host answers each with a **result** or a **fault** and sends **pushes** on its own.
  Nothing else crosses the link.

## Endpoints and TLS

All endpoints live under `https://<host>:<port>/api/tennyo` (`LinkEndpoints.PREFIX`). Plain `http://` and `ws://` are
refused.

| Endpoint                    | Method        | Body                | Answer                                                                              |
|-----------------------------|---------------|---------------------|-------------------------------------------------------------------------------------|
| `/api/tennyo/link/hello`    | `GET`         | none                | `HandshakeResponse`: project, editor, capabilities, security version                |
| `/api/tennyo/link/pair`     | `POST`        | `SignedPairRequest` | `SessionResponse`, `PairingConfirmationResponse` or `RememberedPairResumeResponse` |
| `/api/tennyo/link/discover` | `POST`        | `DiscoverRequest`   | `DiscoverResponse` or `DiscoveryConnectResponse`                                    |
| `/api/tennyo/link/channel`  | `GET`+upgrade | none                | the WebSocket channel                                                               |

- `hello` names the session in the `session` query parameter; `pair` names it in the body. Both send
  `Authorization: Bearer <token>`, where the token is the session token or, while pairing by name, the pairing token
  (see [Pairing by name](#pairing-by-name)). A pairing token works only on `pair`; everywhere else it reads as
  `session_not_found`.
- `discover` takes no bearer token. It answers a pairing offer or a remembered pair's proof.
- Request bodies are capped at 64 KiB (`LinkEndpoints.requestBodyLimitBytes`). A larger body gets `413` before it is
  parsed.
- Errors are a JSON `ErrorResponse`. Its `error` is a `LinkFaultCode` wire name, or one of the pairing refusals in
  `ErrorCodes`: `connection_rejected`, `connection_request_pending`, `connection_request_timed_out`.
- `HandshakeResponse` and `SessionResponse` may carry `editor` (`HostEditor`: `jetbrains`, `vscode`, `cursor`,
  `devin_desktop`), so the phone can point the user at that editor's settings. An unknown value reads as absent.
  Every other enum on the link is strict.

### Pinning

The host has a self-signed TLS identity that it keeps across restarts, so a remembered phone still recognizes it. The
ticket carries the SHA-256 of the certificate's public key. Before every `hello`, `pair`, `discover` and channel open,
the phone pins the ticket's endpoint and its alternates to that fingerprint, and sends the session token only over a
pinned connection.

The phone never falls back to platform trust. If an endpoint ends up without a pin (a host name the platform cannot
parse, a blank fingerprint), the call fails with `LinkFault.Unreachable(UnpinnedAddress)` before a socket opens.

Pins are kept per address **and port**. An IDE and an editor on the same computer share an address but have their own
certificates, and pinning one never replaces the other.

A ticket's `alternateEndpoints` list the host's other LAN addresses, then its overlay addresses (see
[Remote access](#remote-access)). Overlay addresses travel only inside the encrypted ticket.

## Device proofs

Every pairing request and every channel open carries a `DeviceProof` over `SecurityTranscripts.deviceProof`:

```
tennyo-device-proof
<security version>
<endpoint>
<SHA-256 of the exact request text>
<session id>
<SHA-256 of the bearer token this request used>
<host TLS fingerprint>
<device public key>
<nonce>
<signedAtEpochMillis>
```

The device signs it with its P-256 key (ECDSA over SHA-256, DER, base64url). The host checks, in this order:

1. the security version (a mismatch is `protocol_version_unsupported`, never a bad signature);
2. that the device fingerprint is the SHA-256 of the presented key;
3. that `signedAtEpochMillis` is within `LinkLimits.PROOF_FRESHNESS_MILLIS` (5 minutes) of its own clock;
4. the signature;
5. that the nonce is new, and only then records it.

A reused nonce or a stale proof is `replayed`. A bad signature does not use up the nonce.

The request text is exact: the `SignedPairRequest.request` JSON as sent, or for a channel open the resume point
(`lastPushSeq`, or an empty string for a fresh client). The token hash is the hash of the token the request actually
carried. A proof therefore cannot move to another endpoint, session, host, resume point or token.

The channel also requires the proof to come from the device the session approved; any other device is
`wrong_device`.

## Pairing with a ticket

1. `GET /link/hello` checks that the session is reachable and describes it. The phone tries each ticket address and
   keeps the first that answers.
2. `POST /link/pair` with `PairRequest.Claim` asks the IDE to approve the device. For a device that has not paired
   before, an accepted claim returns a `PairingChallenge`: a grid of sixteen words and the challenge to prove against.
3. The phone then sends either `PairRequest.Confirm` (the four words the user taps, in order) or
   `PairRequest.ResumePair` (a pair remembered from an earlier session, proved with its pair secret).
4. Remembered-pair material and rotated pairs are encrypted under a key from an ephemeral ECDH exchange, never under a
   key derived from the pair secret. A leaked secret cannot decrypt a recorded exchange.

[Pairing design](pairing-word-grid.md) explains the word grid, the attempt limit and the version check.

`POST /link/discover` does the same without a ticket. `DiscoverRequest.Discover` asks a host found over DNS-SD
(`_tennyo-link._tcp`) whether it still knows a pair. `DiscoverRequest.Connect` proves that pair, with a device proof
over the connect request, and receives a fresh encrypted ticket.

## Pairing by name

A phone that has never met a host has no ticket and no fingerprint to pin. Instead, the user opens a **pairing window**
in the IDE. It stays open for two minutes (`SessionTimeouts.PENDING_APPROVAL_TIMEOUT_MILLIS`) and starts the session
it belongs to, so a window nobody uses ends with its session. The window carries a name drawn for it and a short id
derived from the host's TLS fingerprint (`HostShortId.of`: the first twelve hex characters of its SHA-256, shown as
`4 4 4`).

`DiscoverRequest.Offer` asks whether a host is in pairing mode. The `OfferResponse` status is:

| Status    | Meaning                                                |
|-----------|--------------------------------------------------------|
| `open`    | a window is open; `offer` carries the `PairingOffer`   |
| `closed`  | the host is reachable but not in pairing mode          |
| `blocked` | the host's discovery lockout is refusing this caller   |

A `PairingOffer` carries the pairing session id, `pairingToken`, host name, short id, IDE label, window deadline and
security version, and nothing else. The project name, its path and the host's capabilities come only after the user
approves the device. Offers are answered from memory: they start no session, read no pair store and do not count
toward `DiscoveryLockoutPolicy`.

The phone then:

1. records the SPKI SHA-256 of the certificate the host presents and pins it for that address and port. This is the
   only place the app accepts a certificate it has not seen, and only for the offer request;
2. drops the host unless `HostShortId.of(observedFingerprint)` equals the offer's `hostShortId`;
3. claims and confirms as above, signing its proofs with the pairing token's hash. It starts at `pair`, not `hello`,
   so the project stays unnamed until the user taps the words.

When the words are confirmed, the response carries the session's `SessionTicket` as `encryptedArtifact`, encrypted
under the pairing ECDH key just as discovery connect does it, and the host closes the window. The session token never
leaves the host in the clear, and the pairing token stops working once the user approves a device.

Pairing works only from the local network. A `REMOTE` caller (see [Origin](#origin)) gets `closed` for an offer and
`session_not_found` for a pairing token, so it never sees a window and cannot start, confirm or approve a pairing.

### Why the words authenticate the host

Nothing out of band carries the fingerprint, yet the words still authenticate the host.
`SecurityTranscripts.pairingConnect` covers the host's TLS fingerprint **and both ephemeral public keys**, and the
tapped words key the MAC over it. A relay that terminates TLS with its own certificate agrees a different transcript
and cannot forward the phone's confirmation to the real host.

The words are not a PAKE secret, though. The phone's MAC covers a transcript the dialed endpoint knows completely, so
a rogue host that the phone pinned could try every selection offline, learn the words and confirm to the real host
with its own device key. The defence is the user: compare the short id on the phone with the one in the IDE before
tapping the words, and decline any approval prompt you did not expect.

## Discovery and listening

The host advertises a DNS-SD service whose instance name is its companion id, with a TXT record holding `v` (security
version) and `p` (`1` while a window is open). The record only orders the phone's probes; nothing trusts it. DNS-SD
renames a taken instance name to `<name> (2)` and so on, so the phone matches a known id with or without that suffix
and probes every match, since a host is often advertised on an address the phone cannot reach.

The listener and the advertisement use the host's private LAN addresses, one mDNS responder per address. Virtual and
VPN adapters (container bridges, WSL, WireGuard, Cloudflare WARP, Tailscale and similar) are used only when nothing
else is up: a VPN usually holds the default route, and a responder there answers into the tunnel instead of the
Wi-Fi the phone is on.

The listener takes its remembered port if it is free, otherwise the first free port in 63343–63383, otherwise any
port, so several IDE windows on one computer each get their own. IntelliJ keeps the port in its global settings;
VS Code keeps one per folder in `workspaceState` (`tennyo.listenerPort`). A phone's stored address therefore survives
a restart.

## Remote access

A paired phone can reach its host from anywhere through a private network the user already runs. The link itself does
not change: the same pinned TLS, device proof and channel run over one more address. No Tennyo server, relay or
account is involved, and pairing stays local.

### Switch and addresses

**Remote access** is a per-machine switch, off by default, stored in `DiscoveryTrustState.remoteAccess`. The panel
disables it while visibility is `NO_ONE`. Both editors pick addresses with `selectListenAddresses`
(`transport/ListenAddresses.kt`):

- **LAN**: RFC 1918 IPv4 on real interfaces, falling back to virtual ones only when nothing else is up. An interface
  named for an overlay network is never LAN, not even as a fallback.
- **Overlay**, only while Remote access is on: IPv4 in 100.64.0.0/10 on any interface (Tailscale, Headscale,
  NetBird), and RFC 1918 on an interface named like ZeroTier (`zerotier`, `zt…`) or WireGuard/NetBird (`wireguard`,
  `wg…`, `netbird`, `wt…`). Name detection misses `utunN`, `feth*` and custom names; callers on such tunnels are
  classified like LAN callers.
- Without a LAN address nothing is selected, because the listener needs one to start. A host whose only private
  address is on `wg0` has no LAN address.
- Selection goes by range and name, not identity. A commercial VPN in 100.64.0.0/10 (Cloudflare WARP) is bound while
  the switch is on; [the gate](#the-gate) protects it, not the selection.

Each overlay address is bound on the LAN port. IntelliJ re-checks addresses every 30 seconds and immediately when the
switch changes; the Node host does the same in `NodeLinkServer.reconcile()`. Tickets are built from the live
addresses, so a newly bound address appears in the next ticket, and the phone learns overlay addresses the next time
it connects at home. DNS-SD never advertises overlay addresses.

### Origin

Each accepted connection gets a `ConnectionOrigin` (`transport/ConnectionOrigin.kt`), computed once:

- `LOCAL`: the socket is bound to a LAN address, the source is an RFC 1918 IPv4 address inside that interface's
  subnet, and the source is not one of the machine's own addresses. IPv4-mapped IPv6 sources are unwrapped first.
- `REMOTE`: everything else, including overlay sockets, public or out-of-subnet sources such as port forwards,
  loopback, unreadable sources, and local proxies (Tailscale Funnel, `cloudflared`, `ngrok`) that connect from the
  machine's own address.

The rule assumes no source NAT. A router that source-NATs a port forward, a Tailscale subnet router or a LAN reverse
proxy can still make a caller look local. The rule narrows pairing to the local network; it does not prove where a
caller is.

### The gate

While Remote access is off, a `REMOTE` connection is closed when accepted, before the TLS handshake and before it
takes an admission permit (IntelliJ: an origin handler ahead of the TLS handler; Node: the server's `connection`
event). A router port forward is therefore refused by default. So is a caller that is local in practice but not by
address: a phone emulator, WSL or Docker on the host connects from one of the machine's own addresses and is
`REMOTE`.

**Reset trust** turns Remote access off, and so does the security lockout that blocks discovery (five counted
failures in fifteen minutes). After an attack the user has to turn it back on deliberately.

### What a remote caller may do

With Remote access on, a `REMOTE` caller can only resume what the phone already holds:

| Request from a `REMOTE` origin                           | Answer                                                  |
|----------------------------------------------------------|---------------------------------------------------------|
| `DiscoverRequest.Offer`                                  | `closed`                                                |
| `pair` with a pairing token                              | `session_not_found`                                     |
| `PairRequest.Confirm`, `PairRequest.ResumePair`          | `unauthorized`                                          |
| `PairRequest.Claim` from the session's approved device   | accepted, as a `SessionResponse`                        |
| `PairRequest.Claim` from any other device                | `wrong_device`; the IDE shows no prompt                 |
| `DiscoverRequest.Discover` and `Connect`                 | as for a local caller                                   |
| `hello`, the channel                                     | as for a local caller                                   |

A remote caller needs a remembered pair's secret, the device key and the pinned certificate. Replays, wrong devices,
expired or stopped sessions and repeated discovery connects are refused exactly as for a local caller.

### Remembered pairs

- **Lifetime.** A remembered pair lasts 14 days (`SessionTimeouts.REMEMBERED_PAIR_TTL_MILLIS`) from the last
  reconnect: every resume and discovery connect issues a fresh pair, and the phone forgets a host unused for as long.
  The long lifetime lets remote reconnects work without pairing again, at the cost of a wider window for a stolen
  phone; revoking the device in the IDE's known-devices list and the rotation on every reconnect limit it. This is the
  only security property remote access relaxes.
- **Lost replies.** The pair a connect replaces stays valid for one discovery challenge lifetime (30 seconds), or
  until the new pair is rotated in turn, so a reply lost on a bad connection can be retried. Revoking a pair revokes
  that grace copy too.
- **Pinning.** The phone probes a known host only at the fingerprint it remembers for it, never at one it merely
  observed.
- **Dialing.** When DNS-SD does not find a known host, the phone probes its stored addresses together: the one that
  answered last, those from the host's last ticket, then the ones the user typed, at most 8 in all. While the pairing
  screen is open it does this at most every 15 seconds. The first answer in that order that is not `UNAVAILABLE` wins. The host shows as
  nearby (DNS-SD) or away (direct dial), and the answering address leads the stored ticket.
- **Typed addresses.** The user can add up to 4 IPv4 literals or DNS names (`host[:port]`, optional `https://`; IPv6 and
  non-LDH names are refused) for a port forward or dynamic DNS. Each is checked once with a pinned `Discover`, kept
  whatever the result, and dialed like a learned address. Whether iOS accepts a pinned host name still needs a manual
  check (`allowsTypedHostNames`); the app never adds an App Transport Security exception.

### Supported networks

| Network                                                    | Status                                                               |
|------------------------------------------------------------|----------------------------------------------------------------------|
| Tailscale, Headscale, NetBird (100.64.0.0/10)              | Supported on any interface; pinned TLS runs inside the tunnel        |
| ZeroTier, WireGuard on the computer                        | Supported, detected by interface name                                |
| WireGuard on the home router                               | Works as is: the phone joins the LAN and dials the stored LAN address |
| Router port forward with a dynamic DNS name                | Manual fallback through a typed address                              |
| Tailscale Funnel `--tcp`                                   | Works through a typed address, as a `REMOTE` caller                  |
| Cloudflare Tunnel, ngrok HTTP, Funnel `--tls-terminated-tcp` | Unsupported: TLS ends at the vendor, which breaks pinning          |
| UPnP-IGD, NAT-PMP, PCP                                     | Unsupported: the host never opens router ports                       |
| Global IPv6                                                | Unsupported: the listener is IPv4 only                               |

A port forward exposes the listener to Internet scanners. Their connections count against the global admission limit
(`MAX_PENDING_TLS_HANDSHAKES`, 32) while per-source limits still apply, so a scan can slow a legitimate phone down.

On Windows, the editor's inbound firewall rule must cover the network category of the overlay adapter (Tailscale's is
Private). Both panels mention Windows Firewall when no network is found.

## The channel

The phone upgrades `/link/channel` and sends one `LinkFrame` per WebSocket text message, as JSON tagged by `op`:

```
Open(sessionId, sessionToken, proof, lastPushSeq?)   phone, first frame
Opened(streamId, commandKeySeed, serverTime, snapshotRequired, securityVersion)
Call(id, seq, mac, call)                             phone
Reply(id, result)                                    host
Fault(id?, fault, message?)                          host
Push(seq, push)                                      host
Part(partId, index, last, text)                      either side
```

The upgrade happens before authentication. The first frame must be a complete (never split) `Open` with a valid
proof, arriving within 10 seconds (`LinkChannel.OPEN_DEADLINE_MILLIS`); otherwise the host closes the socket with
`invalid_call`. An unknown session or wrong token is `session_not_found`. An `Open` past the session's deadline ends
the session and answers `session_expired`.

### Calls

Each `Call` carries a `seq` that strictly increases on its stream, and a MAC over `SecurityTranscripts.callMac`:

```
tennyo-link-call
<security version>
<session id>
<SHA-256 of the bearer token this request used>
<host TLS fingerprint>
<approved device fingerprint>
<stream id>
<seq>
<call id>
<SHA-256 of the call JSON>
```

The key is `SecurityDerivations.commandMacKey(commandKeySeed, sessionToken, streamId, approvedDeviceFingerprint)`,
with the seed taken from `Opened`. A `seq` that does not increase is `replayed` and a wrong MAC is `mac_invalid`.
Either closes the channel, and the call never runs.

Every verified call keeps the session alive. The phone sends `Stats` periodically for exactly that reason, so the
approved-device idle timeout does not fire.

Calls whose effect depends on order (`ShowPane`, `TerminalInput`, `SelectTerminal`, `OpenTerminalWindow`,
`StopSession`) run one at a time, in order. Up to 256 of them may wait; one more closes the channel with `busy`. Other
calls run as soon as a worker is free, so a slow Git read cannot hold up typing. Calls that wait on an agent
(`ChatStart`, `ChatListSessions`, `ChatHistory`, `ChatAuthenticate`, `ChatSetMode`, `ChatSetConfig`) have their own
workers, so an agent that never answers cannot stall the panes.

### Pushes and resuming

Push `seq` values only grow. The phone acknowledges a push once every pane has applied it and sends the last
acknowledged `seq` as `lastPushSeq` when it reopens. The host answers with:

- `snapshotRequired = false` and the missed pushes, if its history (the last 200 pushes, at most 2 Mi characters of
  frame text) still holds them all;
- `snapshotRequired = true` otherwise, or when the phone sent no resume point. The phone then reloads what its panes
  show before applying anything newer.

Panes created while the session was already live (the workspace reopened from Home) have nothing for resumed pushes
to update, so they load a snapshot anyway, over the open channel or on the next open.

After every open, the host also pushes the current state of the pane on screen, so the phone catches up even if it
dropped a push it never acknowledged.

### Long frames

A frame longer than `LinkLimits.MAX_FRAME_CHARS` (256 KiB) is sent as ordered `Part`s under one `partId`, cut so no
part ends inside a surrogate pair, and the receiver reassembles them, up to `MAX_ASSEMBLED_FRAME_CHARS` (8 MiB). Out-of-order parts, an oversized message or too
many unfinished frames close the channel with `frame_too_large` or `invalid_call`. File reads and writes have their own
limit, `MAX_FILE_TEXT_BYTES` (2 MiB); terminal input is limited to 16 KiB.

## Calls and results

| Call | Result | Notes |
|---|---|---|
| `StopSession` | `Done` | ends the session |
| `Stats` | `Stats` | session counters and the host's clock |
| `ShowPane` | `Done` | the pane on screen and what it has selected |
| `FileTree` | `FileTree` | one level of the project tree |
| `ReadFile`, `WriteFile` | `File` | paths resolve inside the project only |
| `EditorTabs`, `EditorTabActionCall` | `EditorTabs`, `Done` | an action is `OPEN`, `SELECT` or `CLOSE` on a `path`; one outside `EditorTabsState.allowedActions` faults `unsupported`; `selectedPath` is null when no tab is selected |
| `LaunchConfigurations`, `SelectLaunchConfiguration` | `LaunchConfigurations` | needs `LAUNCH_CONFIGURATIONS` |
| `LaunchCandidatesForFile` | `LaunchCandidates` | configurations the IDE offers for a project-relative file |
| `StartLaunch`, `StopLaunch` | `Done` | needs `LAUNCH_CONFIGURATIONS` |
| `LaunchTabs`, `SelectLaunchTab`, `CloseLaunchTab` | `LaunchTabs`, `Done` | needs `LAUNCH_CONFIGURATIONS` |
| `LaunchOutput` | `LaunchOutput` | the selected tab's buffered output |
| `TerminalTabs`, `CreateTerminal`, `SelectTerminal`, `CloseTerminal`, `OpenTerminalWindow` | `TerminalTabs`, `Terminal`, `Done` | |
| `TerminalSnapshot` | `TerminalSnapshot` | the full screen, optionally with every style definition |
| `TerminalInput` | `Done` | refusals also appear as terminal output |
| `GitStatus`, `GitDiff`, `GitCommitFileDiff`, `GitBranches`, `GitHistory`, `GitCommitDetail`, `GitPushPreview`, `GitProjectInfo` | the matching `Git*` result | remote URLs never include credentials |
| `GitPush`, `GitCheckout`, `GitCommit`, `GitRollback`, `GitFetch`, `GitPull`, `GitMerge`, `GitCreateBranch`, `GitDeleteBranch`, `GitSaveRemote`, `GitRemoveRemote`, `GitProjectAction` | `GitOperation` | a typed outcome, never a message; see [Git](#git) |
| `ChatSnapshot`, `ChatStart`, `ChatListSessions`, `ChatAuthenticate` | `Chat` | needs `CHAT`; see [Chat](#chat) |
| `ChatHistory` | `ChatHistory` | earlier conversations across agents, newest first |
| `ChatPrompt`, `ChatCancel`, `ChatAnswer`, `ChatAnswerForm`, `ChatSetMode`, `ChatSetConfig` | `Done` | `accepted = false` while a turn runs, without an open session, or when the request was already answered |

Pushes: `Stats`, `Capabilities`, `EditorTabs`, `FileContentChanged`, `LaunchConfigurations`, `LaunchTabs`,
`LaunchOutputChanged`, `TerminalTabs`, `TerminalOutput`, `GitInvalidated`, `Chat`, `SessionEnded`.

### Git

Every host runs the computer's own `git` through the engine's `CliGitPort` (a JVM process under IntelliJ, a Node child
process under VS Code) and parses its machine-readable output. No editor Git API is involved. Repositories are found
under the project roots with `git rev-parse --show-toplevel`.

- Each `GitRepositorySummary` has a `projectPath`: where the repository root sits below the project folder (the first
  project root). It is empty for the folder itself, `/`-separated when nested and `null` outside it; a host that omits
  it reads as empty. Change paths stay repository-relative. Before asking the IDE to open a file, the phone joins
  `projectPath` and the path, folds `.` and `..`, and refuses anything that leaves the folder.
- Git runs non-interactively (`GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=never`, `GIT_EDITOR=true`), with literal
  pathspecs, without optional index locks and with `GIT_ALLOW_PROTOCOL=git:http:https:ssh`, so a remote URL from the
  phone cannot reach `ext::` or local-file transports. Paths go on stdin (`--pathspec-from-file`), never on the
  command line.
- Reads time out after 60 seconds and changes after 10 minutes. A timeout, a cancelled call or a closed session kills
  the process and its helpers (`git-remote-https`, `ssh`).
- Every change shows in the IDE as a cancellable task (an IntelliJ background task, a VS Code notification).
  Cancelling it answers `CANCELLED`.
- One change runs per repository at a time; a second answers `BUSY`. Any change that ran, whatever the outcome, bumps
  the Git revision and pushes `GitInvalidated`.
- `GitCommit` and `GitRollback` name changes by the opaque ids of a status at `expectedRevision`. A stale revision or
  a missing id answers `STALE_TARGET`. Rollback restores tracked paths to `HEAD` in the index and working tree and
  deletes untracked files.
- `GitProjectAction` is `STASH`, `APPLY_STASH`, `CLEAN_UP` or `RESET_HEAD` (`git reset --hard HEAD`, which keeps
  untracked files).
- `GitDeleteBranch` deletes one local branch with `git branch -D` (forced, since the phone has already asked the user;
  unmerged commits stay in the reflog) and never touches the remote. It names the branch by its id in a branch list at
  `expectedRevision`: a stale revision or a missing branch answers `STALE_TARGET`, the current branch `REJECTED`. The
  host marks deletable branches with `GitAction.DELETE` in `GitBranch.availableActions`, and the phone offers deletion
  only for those.
- Specific outcomes: `NON_FAST_FORWARD` (a pull that cannot fast-forward; the phone offers `GitMerge`), `CONFLICTS`,
  `AUTH_FAILED` (the computer's Git has no credentials the remote accepts) and `IDENTITY_MISSING` (no `user.name` or
  `user.email` on the computer).

### Terminals and launches in VS Code

VS Code does not let an extension read a terminal it did not start, so the extension runs its own. Each runs the
default shell (PowerShell on Windows, `$SHELL` elsewhere) on the editor's bundled `node-pty`, appears in the editor's
terminal panel, and keeps its screen in a headless xterm that `TerminalSnapshot` and the output pushes read. The
editor's panel sets the grid size. Terminals the user opened in the editor are not listed, and an editor whose
`node-pty` cannot be loaded does not report `TERMINAL`.

Launch configurations are the open folders' launch configurations, started without debugging, plus the editor's tasks.
A launch configuration's output comes from the debug adapter's `output` events. A shell or process task runs on the
extension's own pseudo-terminal, still in the editor's task terminal with its problem matchers, so its output reaches
the phone. A task another extension runs itself (a custom execution) can be started and stopped from the phone, but its
output stays in the editor. Each start is a launch tab until the phone closes it.

### Chat

The host is the client of an [ACP](https://agentclientprotocol.com) agent (protocol version 1). The phone sees one
conversation per session as a `ChatState`: the configured `agents`, the running `agentId`, whether a turn is `busy`,
the transcript `entries` (`prompt`, `message`, `thought`, `tool` with an optional `diff`, `permission`) and a typed
`failure` (`start_failed`, `sign_in_required`, `agent_exited`, `turn_failed`).

- **Agents come only from the computer's configuration:**
  - `~/.jetbrains/acp.json` (`agent_servers`);
  - Devin Desktop's agents file in the ACP registry format (`npx`, `uvx` or an installed binary for this platform):
    `~/.windsurf/acp/registry.json`, or `%APPDATA%/Code/User/acp/registry.json` on Windows;
  - in the VS Code family, the `tennyo.acp.agentServers` setting;
  - inside Devin Desktop, the agents its `devin.acp.enabledAgents` setting turns on. Devin keeps no list of them on
    disk, so the host reads the public ACP registry
    (`https://cdn.agentclientprotocol.com/registry/v1/latest/registry.json`) and starts each one the way Devin does:
    `npx` and `uvx` agents through those launchers, an archive-released agent by its command name from `PATH`, and
    the bundled `devin-cli` as `devin acp`;
  - in IntelliJ, the ACP registry its AI chat caches, minus the agents turned off there; a release archive is
    downloaded on the agent's first start.

  The phone names an agent by id and never sends a command. A host reports `CHAT` while at least one agent is
  configured or its agent host is attached.
- **`ChatAgent.installed`** says whether the agent is already on the computer (its binary unpacked, its `npx` package
  cached) or will be downloaded on first start; the phone lists installed agents first. It defaults to `true`, so an
  agent the host cannot judge is not shown as a download.
- **`initialize`** sends `clientInfo` with `name`, `title` and the `version` ACP requires.
- **No client capabilities** are offered (`fs` and `terminal` are false), so the agent uses its own tools in the
  project folder and asks first through `session/request_permission`. Any other agent request gets JSON-RPC `-32601`.
  Permissions reach the phone as `permission` entries; `ChatAnswer` picks one of the agent's options, and ending the
  turn or the session cancels whatever is still open.
- **Sessions.** `ChatStart` opens a new session or, with `sessionId`, loads one the agent listed through
  `ChatListSessions` (`session/list`, `session/load`); a loaded session's history arrives as updates and fills the
  transcript. Modes and settings (`session/set_mode`, `session/set_config_option`) and slash commands
  (`available_commands_update`) are mirrored in the state; a command is a prompt starting with `/name`. If an agent
  reports a setting of category `mode`, its legacy `modes` are left out so the phone shows a single mode picker.
- **History.** `ChatHistory` returns earlier conversations across agents as `{agentId, sessionId, title, updatedAt}`,
  newest first, for `ChatStart` to resume. It lists only what needs no agent started: the sessions of every VS Code
  agent-host provider, and the running ACP agent's `session/list`. A source that fails is left out. History is
  resumable sessions, not a list of chat tabs open in the editor.
- **Selection.** Each configured agent has its own backend, and one `ChatState` is selected per link session.
  Switching agents keeps the previous agent's process and session until the link session ends, but only the selected
  backend publishes its transcript. A permission request left pending on the backend being switched away from is
  cancelled. Hiding the conversation on the phone only returns to the agent picker; the host session keeps running.
- **Sign-in** happens on the computer. When opening a session answers `auth_required`, the state lists `authMethods`.
  An agent method starts with `ChatAuthenticate`, and the agent opens its own sign-in; a terminal method carries the
  command line to run in a terminal there. Either way, the waiting session then opens.
- **Prompts** carry `attachments`: images (only when the agent's `promptCapabilities.image` is set) and project files
  as `resource_link`s. A file path must resolve inside the project, otherwise it faults `target_missing`.
- **Plans and forms.** A `plan` update replaces the transcript's plan entry. A form `elicitation/create` becomes a
  `form` entry the phone answers with `ChatAnswerForm`, with values typed as the schema declares. A URL elicitation is
  declined, because the phone cannot open the agent's link on the computer.
- **VS Code's agent host** (Copilot and the other providers the editor runs) adds agents with ids `ahp:<provider>`.
  The extension attaches to an agent host the editor already runs, found through
  `<userData>/agent-host/local-endpoint/entries/*.json` (entries whose process is gone are skipped), and speaks
  [AHP](https://github.com/microsoft/agent-host-protocol) 0.9 through its package's client and reducers. `ChatStart`
  creates a session (`createSession` with the project folder) or follows one from `ChatListSessions`
  (`listSessions`), and the transcript comes from the session's default chat. Pending tool confirmations become
  `permission` entries answered with `chat/toolCallConfirmed`, input requests become `form` entries answered with
  `chat/inputCompleted`, a prompt is `chat/turnStarted` and cancelling is `chat/turnCancelled`. Prompts are text only
  (attachments fault `unsupported`), and modes and settings are not offered. A provider that answers `AuthRequired`
  (`-32007`) is signed in from VS Code, after which `ChatAuthenticate` retries the start.
- **Load.** Every change pushes the whole `ChatState`, coalesced over 100 ms, so a streamed answer costs at most ten
  pushes a second. A phone that missed pushes reloads with `ChatSnapshot`. The agent process ends with the session.

## Fault codes

`LinkFaultCode` is the single vocabulary for refusals, both on the channel and in HTTP error bodies.

| Code | Meaning |
|---|---|
| `invalid_call` | the frame or its arguments could not be read |
| `unauthorized` | no proof, or one that does not verify |
| `wrong_device` | not the device this session approved |
| `replayed` | a `seq` that did not increase, or a proof reused or too old |
| `mac_invalid` | the call's MAC does not match |
| `session_expired`, `session_not_found` | the session is over, or never existed |
| `protocol_version_unsupported` | the two sides use different security versions |
| `frame_too_large` | over the frame or reassembly limit |
| `target_missing` | the file, tab, terminal or task is gone |
| `unsupported` | the IDE cannot do this |
| `busy` | too many calls are already waiting |
| `file_too_large` | over the mobile editing limit |
| `git_target_stale`, `git_cursor_invalid`, `git_missing_upstream`, `git_platform_failure` | Git refusals |
| `operation_failed` | anything else the host could not finish, including host-side timeouts |

## Session lifetime

A session ends when the user stops it in the IDE or the app (`StopSession`), when pairing approval times out
(`PENDING_APPROVAL_TIMEOUT_MILLIS`, 2 minutes), when the approved device stays silent for
`APPROVED_DEVICE_IDLE_TIMEOUT_MILLIS` (3 minutes), or after `ABSOLUTE_SESSION_TIMEOUT_MILLIS` (12 hours). The host
pushes `SessionEnded` with the reason and closes the channel, and the phone stops treating the session as resumable.

While the app is in the background, its notification keeps the session alive by opening a channel for one `Stats`
call at a time.

A remembered pair outlives sessions: it lasts 14 days from the last reconnect, on both the host and the phone (see
[Remembered pairs](#remembered-pairs)).
