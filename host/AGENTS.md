# Host Adapter Guide

Applies to `host/intellij/` and `host/vscode/`. Engine code follows [engine/AGENTS.md](engine/AGENTS.md).

- The adapters only connect `:host:engine` to an IDE: lifecycle, platform services, UI, persistence, and the
  engine's `Ide*Port` implementations. A route, session rule or security check written in an adapter never reaches
  the other IDE, so it belongs in the engine.
- Reflection is only for unstable, private or missing IntelliJ APIs. Isolate it, say why the direct API cannot be
  used, and degrade gracefully when the class or method is absent.
- The listener, TLS identity and credential storage are adapter-owned security boundaries; keep their checks
  explicit and tested.
- IntelliJ user-facing strings live in `host/intellij/resources/messages/HostBundle.properties`.
- Run `./gradlew :host:intellij:test` (see the `host-tests` skill) and `./gradlew :host:vscode:packageDebugVsix`.
