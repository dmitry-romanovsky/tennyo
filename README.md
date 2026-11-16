# Tennyo

Tennyo lets you work on code from your phone, in one of two ways:

- **Connect to an IDE.** Browse and edit project files, run launch configurations, use terminals, work with Git and
  chat with coding agents in an IntelliJ-based IDE or a VS Code-based editor (VS Code, Cursor, Devin Desktop).
- **Work locally.** Clone a Git repository into the app's private storage, then edit, commit, branch, pull and push
  without a computer.

The phone talks to the IDE directly, over your local network or a private network you already run, such as Tailscale
or WireGuard. You approve each phone in the IDE, and every connection uses pinned TLS. There is no Tennyo server or
account.

## Building

You need a JDK 21 or newer to run Gradle, the Android SDK, and Python 3.13 for the app's bundled Python packages.
Everything compiles for Java 21, so Gradle needs a JDK 21 toolchain as well. Node.js for the VS Code extension is
downloaded by the build. If the build cannot find Python 3.13, point `CHAQUOPY_BUILD_PYTHON` (or `chaquopy.buildPython`
in `local.properties`) at its executable.

```bash
./gradlew :app:android:assembleDebug           # Android app
./gradlew :host:intellij:buildIdeDebugPlugin   # IntelliJ plugin
./gradlew :host:vscode:packageDebugVsix        # VS Code extension
```

Tests and static analysis:

```bash
./gradlew testAndroidHostTest :shared:contract:allTests :shared:crypto:allTests :host:engine:allTests :host:intellij:test detekt
```

The iOS app in `app/ios` builds on macOS with Xcode.

## Layout

| Path               | Contents                                                                               |
|--------------------|----------------------------------------------------------------------------------------|
| `app/`             | Mobile app: design system, link client, features, Android and iOS shells               |
| `host/engine/`     | Host protocol engine shared by both IDE adapters                                       |
| `host/intellij/`   | IntelliJ Platform plugin                                                               |
| `host/vscode/`     | Extension for VS Code and compatible editors                                           |
| `shared/contract/` | Wire types and protocol constants                                                      |
| `shared/crypto/`   | Security constructions both sides use                                                  |
| `docs/`            | [Link protocol](docs/link-protocol.md) and [pairing design](docs/pairing-word-grid.md) |

Tennyo is released under the [MIT License](LICENSE).
