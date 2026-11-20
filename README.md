# DSH Native — Android & Windows client for DeepSeek Harness

Native (non-WebView) clients for a running [DeepSeek Harness](https://github.com/deepseek-ai) (`dsh web`) server.
Enter the **environment URL** (e.g. `https://your-host/dsh/`) and the **token** printed by `dsh web`; the app talks to
DSH's own APIs directly.

Built with **Kotlin Multiplatform + Compose Multiplatform**: one shared codebase (`shared/`) for protocol, data and UI,
with thin platform shells for Android (`androidApp/`) and desktop/Windows (`desktopApp/`).

## Features
| Area | Status |
|---|---|
| Connect: URL + token → cookie exchange, multiple saved environments, auto-reconnect, re-auth prompt | ✅ |
| Sessions & workspaces: list, search, create (workspace / remote folder picker / agent preset), rename, fork, pin, archive, workspace rename/remove | ✅ |
| Live chat: streaming text & reasoning, tool cards (bash, edit diff, write, read, search, web, todo…), markdown, images, notices, compaction, commands | ✅ |
| Composer: queue / steer / stop, queued-message edit/steer/remove, image & file attachments, `/` commands, `@` file & session mentions | ✅ |
| Approvals & agent questions (incl. plan review) inline, global “needs input” banner, Android notification actions (Allow/Reject) | ✅ |
| Model + reasoning effort, permission mode, agent preset pickers; context usage | ✅ |
| Session export (ZIP) | ✅ |
| Files & document preview, terminal, changed files/diffs, plans, goals, subagents, jobs, trajectory | Phase 2 |
| Schedules, server settings (models/providers/API keys, account, plugin pages), plugin manager, voice input | Phase 3 |

## How it talks to DSH
All details were derived from the DSH sources (`packages/client/connection`, `packages/api/gateway`):

- **Auth**: `GET <base>/?token=T` → `303` + `Set-Cookie: dsh-auth-…` (30 days, HMAC-signed). Only that cookie is stored
  (Android Keystore-encrypted prefs / Windows DPAPI). The token itself is never persisted. When DSH restarts the token
  changes but the cookie stays valid.
- **Unary RPC**: `POST <base>/api/<namespace>/<method>` with `{"type":"client-request","rpcId","method","payload":{"args":{…}}}`.
- **Streams**: one WebSocket `<base>/api/remote.mux` multiplexing `open/item/end/cancel/error` frames; the `$events` stream
  carries `ready`, `emit` notifications and `waterfall` requests (approvals, questions) answered via `POST api/$events/result`.
- **Typed API**: `shared/src/commonMain/kotlin/dsh/gen/` is **generated** from DSH's own Typert contracts (135 methods,
  28 namespaces). Regenerate after upgrading DSH:

  ```bash
  ./gradlew regenContract -PdshRoot=/path/to/deepseek-harness   # needs node + the DSH checkout's node_modules
  ```
  (`tools/contract-export/export.mjs` → `contract/dsh-contract.json` → `tools/codegen/generate.mjs`.)

## Build
Requirements: JDK 17, Android SDK (compileSdk 37). `local.properties` → `sdk.dir=/path/to/Android/Sdk`.

```bash
./gradlew :androidApp:assembleDebug          # APK → androidApp/build/outputs/apk/debug/
./gradlew :androidApp:assembleRelease        # debug-signed release APK for sideloading
./gradlew :desktopApp:run                    # run the desktop app
./gradlew :desktopApp:packageMsi             # Windows installer (run on Windows)
```

## Tests
```bash
./gradlew :shared:desktopTest                                   # offline protocol/reducer tests
DSH_URL=https://host/dsh/ DSH_TOKEN=… ./gradlew :shared:desktopTest --tests 'dsh.LiveServerTest'
DSH_LIVE_PROMPT=1 …                                             # also sends one tiny prompt (uses model quota)
DSH_URL=… DSH_COOKIE=… ./gradlew :desktopApp:screenshots         # headless screenshots of the real UI
```

## Notes
- nginx (or any proxy) in front of DSH must forward WebSocket upgrades for `/api/remote.mux` and keep `Host`/`Origin`
  acceptable to DSH (loopback or `--trusted-host`).
- Not portable natively: DSH "creator" Cordis plugins that execute browser JavaScript; the web UI's embedded browser tab
  opens the system browser instead.
