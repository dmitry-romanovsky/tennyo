# Tennyo Agent Guide

Applies to the whole repository. Files under `host/` also follow [host/AGENTS.md](host/AGENTS.md), and files under
`host/engine/` follow [host/engine/AGENTS.md](host/engine/AGENTS.md). Project skills live in `.agents/skills/`; load
the ones whose description matches the area you touch.

## Module boundaries

- A feature module (`:app:feature:*`) depends only on `:app:design`, `:app:link` and `:shared:contract`. The one
  exception is `:app:feature:local`, which uses `:app:feature:git` for its Git pane. Features are composed in
  `:app:shared` (root component, DI, and the workspace that hosts the panes).
- `:app:shared` is a KMP library (`com.android.kotlin.multiplatform.library`), so it has no build types or
  `BuildConfig`; see `AndroidBuildConfig.kt`. `:app:android` is only the installable shell (manifest, icons,
  signing, versioning, proguard) and holds no product logic.
- Build conventions live in `build-logic/`. A module script declares only its targets, dependencies and
  module-specific tasks.
- Each workspace pane is its own component with its own `FeatureStore`. The Git pane reads through a
  `GitRepositorySource`, never through contract DTOs, so the local workspace can back it with JGit.

## Protocol

- The app and the hosts are one protocol surface and change together. The link is three public HTTPS endpoints
  (`hello`, `pair`, `discover`) and one authenticated WebSocket carrying `LinkFrame`s.
  [docs/link-protocol.md](docs/link-protocol.md) specifies it and is updated in the same change as the code.
- Wire constants and DTOs (endpoint, call and push names, fault codes) are declared once in `:shared:contract`.
  No consumer redeclares them.
- Every transcript, proof, MAC, protected payload, key agreement and secret comparison lives once in
  `:shared:crypto`. Both sides call it; never re-implement a construction or compare a secret with `==`.
- Protocol logic lives in `:host:engine`; the IntelliJ and VS Code modules only adapt it.
- Security-sensitive changes need tests for replay, wrong device, expired session, stopped session, reconnect, and
  malicious path or input.

## Local workspace and settings

- The local workspace keeps everything in app-private storage (`filesDir/workspaces/<slug>`, with a JSON index
  beside it) and never asks for a storage permission. Every file access goes through `WorkspaceFiles.resolve`, which
  rejects paths that leave the workspace. A platform opts in by binding a `LocalWorkspaceEntry`.
- Git credentials are encrypted with an Android Keystore key and excluded from backups.
- Settings are typed `SettingKey<T>` values over multiplatform-settings, declared by the module that owns them.
  Secrets stay in platform secure storage.

## UI

- Visible strings live in the owning module's `composeResources/values/strings.xml`. Code outside composition
  returns a typed value (an enum, a sealed issue or `UiText`) that the UI resolves; it never builds visible text, and
  logic never keys on it. The Android session notification uses Android resources.
- Icons come from `TennyoIcons` in `:app:design`; add a vector drawable there rather than an icon library.

## Before finishing

- Write the test that proves the behavior first; for a bug fix, a failing test that reproduces it.
- Comments explain only what the code cannot: protocol compatibility, security decisions, threading constraints or
  non-obvious product behavior.
- Run the affected module's tests and `./gradlew :<module>:detekt`. Existing findings are baselined per module in
  `config/detekt/baseline-<gradle-path>.xml` (`:app:shared` → `baseline-app-shared.xml`); a baseline may shrink but
  never grow.
