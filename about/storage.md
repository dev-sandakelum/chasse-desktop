# Storage — every place data lives

`~` = `System.getProperty("user.home")`; everything the app persists lives
under `~/.chasse-studio/` plus the one user-chosen decks folder.

## On disk

| Path | Owner | Contents | Lifetime |
|---|---|---|---|
| `~/.chasse-studio/app.properties` | `AppConfig` | User config: `app.url`, `server.port`, `network.preferred-prefix`, `remote.page-title`, `downloads.dir` *(NEW 1.3.0 — written after every Save-as, never cleared, only rewritten on change)* | Until the user edits it; wins over the bundled `app.properties`, loses to CLI flags |
| `~/.chasse-studio/config.json` | `FsService` | `{ "decksRoot": ..., "confirmedRoot": ... }` — the chosen decks folder + the folder the user already granted via the YES/NO confirm | Persists across restarts; written atomically |
| `~/.chasse-studio/app.lock` | `AppInstanceGuard` | Empty file holding the exclusive single-instance `FileLock` (GUI mode only) | Released by the OS when the process exits, **even on crash** — no stale-lock recovery needed |
| `~/.chasse-studio/jcef-bundle/` | jcefmaven / CEF | Downloaded Chromium natives (~100 MB) on first run — an **install cache**, nothing else | Safe to delete; re-downloaded on next start (unless natives were bundled via `-Pwindows` etc.) |
| `~/.chasse-studio/browser-profile/` | CEF (`cache_path`) | **NEW 1.3.0.** The real Chromium profile of the embedded webview: cookies, IndexedDB, localStorage of the web app. Persists across restarts; single-owner (locked by `AppInstanceGuard`) | Persists until deleted. **Before 1.3.0 this did not exist — no `cache_path` was set, so the profile was IN-MEMORY and the web app's IndexedDB fallback vanished on every restart (the root cause of "the desktop app cannot save"). Deleting it loses the embedded browser's storage — NOT the decks folder |
| `~/.chasse-studio/` (as `root_cache_path`) | CEF | Chromium's per-install global caches (e.g. GPUCache, component updater state) | Same as above; safe to delete while the app is closed |
| The user-chosen decks folder (`config.json → decksRoot`) | `FsService` (granted via `confirmedRoot`) | Real deck folders: one folder per deck id (`^[a-z0-9][a-z0-9-]{0,63}$`), each containing `deck.json` + slides/assets — the **only durable deck data in desktop mode** | Until the user deletes it; re-granting a new folder asks again only if it is not `confirmedRoot` |
| `target/package/`, `target/installer/` | Maven / jpackage | Build outputs (jar + copied runtime deps; app-image / dmg / deb) | Build artifacts, disposable |

## In memory (gone on restart, by design)

| Data | Owner | Notes |
|---|---|---|
| Rooms + join tokens (12-hex `SecureRandom` per room) | `SyncRooms` | A restart invalidates every phone link/QR — the web app must re-join and push a fresh `sync-token` |
| Server log ring (last 200 lines) | `ServerManager` | Seeds `getServerLog`; older lines are dropped |
| Loopback file-server token (48 hex) + port | `LoopbackFileServer` | Per-session; all previously handed-out `fs.fileUrl` URLs die with the process |
| Phone sessions (sessionId → context, last activity, bound deck) | `PhoneWsHandler` | Heartbeat 5s; 15s silence closes |
| Download bookkeeping (id → suggested name, last progress push, `lastFolder`) | `DownloadBridge` | `lastFolder` is the reveal target of `openDownloadFolder` |
| `currentUrl` (trust anchor) | `DesktopBridge` | Maintained by the display handler's `onAddressChange` |
| Deck list cache | `FsService` | 2s TTL; invalidated by every mutation |

## What is deliberately NOT stored

- No passwords, no telemetry, no analytics, no crash reports.
- Phone tokens are never written to disk (a stolen old QR link must die with
  the session).
- The loopback session token is never persisted (URLs handed to the page in a
  previous session are dead).
