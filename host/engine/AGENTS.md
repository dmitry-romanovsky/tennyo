# Host Engine Guide

Applies to `host/engine/`, the IDE-independent host: transport, auth, pairing, sessions, calls, pane controllers and
discovery, shared by `:host:intellij` and `:host:vscode`. A change here changes every IDE at once.

- Targets are `jvm` and `js(nodejs)`. `commonMain` imports nothing from `com.intellij`, `io.netty`, `java.`, `javax.`,
  Node or VS Code; platform code lives in `jvmMain` and `jsMain` behind the expect/actual primitives in `platform/`.
- The engine reaches the IDE only through interfaces it owns: the `Ide*Port`s, `HostPlatform`, the discovery ports
  and `EventConnection`. A port grows only when a controller needs the capability from every IDE.
- Shared mutable state uses `HostMonitor`, `kotlin.concurrent.atomics` and `HostConcurrent*`; blocking I/O runs on
  `hostBlockingDispatcher`. Log through `HostLogger`; the adapters decide where lines go.
- File calls resolve every client path through `resolveExistingProjectPath` before a port touches it. Never trust
  client paths, file names, encodings or terminal input.
- Accepted device nonces expire and are cleaned up. Request limits are bounded; a call that bypasses throttling
  explains why next to the policy.
- Crypto comes from `:shared:crypto`; the engine adds none. A change to a crypto actual must keep
  `PlatformCryptoVectorTest` passing on every target.
- The package carries the context, so types need no product prefix. The phone's connection is the link, IDE
  interfaces are ports, and what the phone shows per feature is a pane.
- Tests live in `commonTest` and run on the JVM and Node; use `jvmTest` only for a test that needs JVM reflection.
  Before handing a change back, run `:host:engine:allTests`, `:host:engine:detekt`, `:host:intellij:test` and
  `:host:vscode:packageDebugVsix`, which smoke-tests the bundle.
