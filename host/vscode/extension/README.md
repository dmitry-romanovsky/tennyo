# Tennyo Companion

Tennyo Companion connects VS Code and compatible editors, such as Cursor and Devin Desktop, to the Tennyo mobile app.
From your phone you can edit project files, run launch configurations and tasks, use terminals, work with Git and chat
with coding agents.

The phone connects to the editor directly, over your local network or a private network you run. Connections use
pinned TLS and a key on the phone that you approve once. There is no Tennyo account or relay server.

## Pair a phone

1. Click the Tennyo status bar item or run **Tennyo: Manage Connection**.
2. Choose **Open pairing mode**.
3. In the app, pick this computer, then accept the request in the editor.
4. Tap the words the editor shows, in order, in the app.

Pairing mode stays open for two minutes; **Keep open 2 more minutes** extends it. Afterwards, choose **Allow only
known devices** to let paired phones reconnect, or **Turn off** to refuse all connections. **Manage known devices**
lists paired phones and lets you remove them.

VS Code's Agents window runs only extensions allowed by the `extensions.supportAgentsWindow` setting. Tennyo offers to
add itself there, or you can run **Tennyo: Show in the Agents Window**.

## Requirements

VS Code 1.126 or newer, or another editor that provides the same extension API.

## Build

From the repository root:

```bash
./gradlew :host:vscode:packageReleaseVsix
```

Install the VSIX from `host/vscode/build/vsix/` with the editor's **Install from VSIX** command.
