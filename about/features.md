# Features, files and verification status

Statuses reflect the audit worklog (`audit-phase-0`, `java-fixes`, `web-fixes`):

- **verified (build/test)** — proven by `mvn package` (BUILD SUCCESS,
  FsServiceTest 21/21) or by unit/contract tests.
- **verified (live)** — proven by running the actual thing (headless server
  checks done in this sandbox).
- **verified (API surface)** — the JCEF API existence was verified against the
  actual jar (jcef-17e805a, Chromium 152.0.7977.83) via `javap` + the code
  compiles against it; the behavior has **not** been executed on a real Windows
  desktop in this sandbox.
- **cross-repo verified** — verified by the web repo's tests (contract mock
  35/35, Playwright E2E against a full Java mock 16/16).

## The window & webview

| Feature | Files | Status |
|---|---|---|
| Minimal native title bar (32 px, flat, no border/gradient/shadow): logo, quiet File / View / Help text menus, the page title muted in the centre, thin − □ × controls (close turns red on hover); no wordmark/tagline (the web toolbar carries the brand); drag-to-move, double-click = maximize/restore | `TitleBar.java` | rendered to PNG in both themes via a Swing harness (see git history of this row); live run pending |
| Bar follows the web light/dark theme automatically — painted in the web app's own chrome palette (`BarTheme`: `--ss-panel` surface, `--ss-ink*`/`--ss-mut` text) so it is seamless with the web toolbar; the page reports `html[data-ss-theme]` + its computed colours through the `ui.theme` bridge command (script injected after every load, re-sent on every toggle via MutationObserver — the web app is NOT modified); last theme remembered in `app.properties` (`ui.theme`) so the next launch starts in it (no flash) | `Main.THEME_SYNC_JS`, `DesktopBridge` (`ui.theme`), `BarTheme`, `AppConfig.uiTheme` | `BarThemeTest`: both built-in palettes WCAG AA (≥ 4.5:1) for every text/surface pair, colour parsing, fallbacks |
| Resize handles for the undecorated window (4 px rim in the bar colour, all edges + corners, min 980×640; off while maximized / fullscreen) | `ResizeBorder.java` | geometry unit-tested (`BarThemeTest`) |
| File / View / Help menus (Change app URL…, Reload, Exit; Toggle Fullscreen; About) with dark-styled popups | `Main.java` (buildMenuBar) | verified (API surface) |
| `Ctrl+F11` fullscreen toggle, double-click title bar toggle, phone-triggered fullscreen | `Main.java` (registerF11Toggle, enter/exitFullscreen, RemoteControlListener) | verified (API surface) |
| Leaving fullscreen without a phone: `Esc` / `F11` / `Ctrl+F11` (CEF key handler — the webview has focus, so the Swing dispatcher never sees these keys) and a floating "Exit full screen" pill (top-centre; shown 3.5 s on entry and whenever the pointer touches the top edge) | `Main.java` (`fullscreenKeyAction`, `showFullscreenHint`) | key logic unit-tested (`WindowAreaTest`); window behaviour not run against a live webview |
| Maximize keeps the taskbar visible (undecorated frames otherwise cover the whole screen): `setMaximizedBounds` = monitor minus taskbar insets, re-measured per maximize / window move; true fullscreen still covers the whole monitor | `Main.java` (`usableArea`, `workArea`, `maximizeKeepingTaskbar`) | unit-tested + verified on a real display (1536×864 monitor, 48 px taskbar: 864 → 816 px) |
| JCEF bootstrap + first-run Chromium natives download (~100 MB into `~/.chasse-studio/jcef-bundle`, console progress) | `Main.java`, `pom.xml` (jcefmaven 152.0.6) | verified (build); download itself exercised in earlier runs (worklog audit-phase-0 lists the ~100MB note; behavior on Windows not re-run in this sandbox) |
| **NEW 1.3.0** Persistent browser profile (`cache_path` `~/.chasse-studio/browser-profile`, `root_cache_path` `~/.chasse-studio`) — the root-cause fix for the save bug | `Main.java` | verified (API surface + root-cause analysis against the jar: `CefSettings.cache_path` defaults null → in-memory). **Not yet run on Windows** — the definitive end-to-end check (create deck → restart → data survives) should be done there |
| **NEW 1.3.0** Single-instance lock (second launch → message + exit) | `AppInstanceGuard.java`, `Main.java` | verified (code + `FileLock` semantics); not executed on Windows |
| First-run app-URL prompt (only when `app.url` is blank), File → Change app URL… | `Main.java` (promptForAppUrl), `AppConfig.saveUserAppUrl` | verified (API surface) |

## Native UX the webview can't do alone (all NEW 1.3.0)

| Feature | Files | Status |
|---|---|---|
| Native downloads: `CefDownloadHandler` → Save-as `JFileChooser` (EDT), suggested name sanitized, remembered folder (`downloads.dir`), extension filter | `DownloadBridge.java` | verified by API-surface inspection + contract tests; **not yet run on Windows** |
| Download events pushed to the page (`started/saving/progress/completed/failed`, progress throttled 250 ms) → web app toasts + "Open folder" | `DownloadBridge.java`, `DesktopBridge.pushEvent` | same as above; web-side toasts cross-repo verified (e2e-desktop-mock) |
| `openDownloadFolder` v1 action — reveals the last completed download's folder only, never arbitrary paths | `DownloadBridge.openLastDownloadFolder`, `DesktopBridge` | verified by API-surface inspection; not yet run on Windows |
| Cancel the Save-as dialog → clean CEF cancel via `Continue("", false)` | `DownloadBridge.java` | verified by API-surface inspection; not yet run on Windows |
| Native JS dialogs: `alert`/`confirm`/`prompt` → Swing on EDT; `beforeunload` guard → Yes/No | `WindowDialogs.jsDialogs` | verified by API-surface inspection (handler + Adapter exist in the jar); not yet run on Windows. Belt-and-braces: the web app also replaced its own `window.prompt` uses with in-app dialogs (cross-repo verified) |
| Popups / `target=_blank` → the system browser, JCEF popup cancelled | `WindowDialogs.popupsToSystemBrowser` | verified by API-surface inspection; not yet run on Windows |

## Sync server & phone remote

| Feature | Files | Status |
|---|---|---|
| Start/stop/restart the LAN server from the page (bridge actions), status + log panel data | `ServerManager.java`, `DesktopBridge.java` | **verified (live)**: Javalin on 0.0.0.0:8765, `/health` OK, `/remote` OK, `/api/status` OK (worklog `java-fixes`) |
| LAN address scan + 5s watcher (RFC1918 only, preferred prefix, virtual adapters de-prioritized and labelled) + manual selection | `LanAddresses.java`, `ServerManager.java` | verified (build + logic); live on this sandbox's interfaces, Windows adapter set not exercised |
| QR link built by the web app from the selected address + port + room token | web repo (uses `getServerStatus`/`joinRoom`/`sync-token`) | cross-repo verified |
| Rooms: join/`setSlide`/`nav`/`reportSteps`/`syncDeck`/`setNotes`, canonical state identical for bridge + phones, MAX_SLIDES 500, steps clamp 1..50, notes ≤8000, 2h idle eviction via 10-min sweeper | `SyncRooms.java` | **verified (live)**: WS join with valid token OK, bad token → "Invalid or expired link token", unbound sessions cannot mutate state |
| Phone WebSocket: token-checked join, nav/set-slide/fullscreen/get-state/ping, heartbeat 5s, 15s silence closes, state/presence/ui/bye broadcasts | `PhoneWsHandler.java` | **verified (live)** (join/token rejection); heartbeat timing verified by code only |
| Phone fullscreen actions → desktop window via `RemoteControlListener` on the Swing EDT | `Main.java`, `ServerManager.fireFullscreen` | verified (API surface); not executed on Windows |
| Self-contained dark phone page: offline, reconnect with 1.5s/3s/5s backoff, own 5s heartbeat + 15s silence reconnect, Screen Wake Lock (+ re-request on visibility change), swipe gestures, expired-token card | `remote/index.html`, `RemotePage.java` | verified (live, `/remote` served); browser-side behaviors verified by reading the page code — not exercised on a physical phone in this sandbox |
| `remote.page-title` actually applied to the served page | `RemotePage.java`, `ServerManager` (`/remote` route) | verified (code); was parsed-but-unused dead config before this pass |
| Headless diagnostic mode (`--headless`, `--room=demo` demo room + printed join link) | `Main.runHeadless` | **verified (live)** — this is the mode the audit ran |
| Port-in-use probe with a clear error | `ServerManager.start` | verified (code + error message) |

## fs capability (the decks folder)

| Feature | Files | Status |
|---|---|---|
| `hello` capability discovery → `{app:"chasse-desktop", version:"1.3.0", capabilities:["sync","fs","downloads"]}` | `DesktopBridge.handleCmd` | verified (build); cross-repo contract test drives the same shape |
| Native folder choice + YES/NO grant remembered in `confirmedRoot`; **chooseRoot params honored (title/startPath/hint)** | `FsService.chooseRoot` | verified (build + unit tests for the state machine; the Swing dialog itself needs a display — not executed here). Params were a silent contract mismatch before this audit |
| Every file op: `getRoot`, `listDecks`, `readText`, `writeText`, `createDeck` (deck.json LAST), `deleteDeck` (verified gone), `duplicateDeck` (patched deck.json), `listFiles`, `delete`, `fileUrl` | `FsService.java`, `DesktopBridge.dispatchFs` | **verified (tests)**: FsServiceTest 21/21 (confinement, symlink refusal incl. missing-file-via-symlinked-parent, lifecycle, atomicity, limits, error codes) + cross-repo: contract mock 35/35, E2E new-deck → save → reload-persist 16/16 |
| Loopback file server: 127.0.0.1:random, 48-hex token, Host/Origin checks, ETag/Range, atomic PUT uploads ≤256 MiB, PNA preflight | `LoopbackFileServer.java` | verified (tests, cross-repo: loopback URL/upload/token-404 covered by the web contract test); not run on Windows |
| Trust enforcement: fs commands → `FORBIDDEN` on untrusted pages; pushes gated by trust | `DesktopBridge.java` | verified (code + cross-repo contract test's hello gating) |

## Honest limits (what is NOT yet verified)

- **Nothing GUI/JCEF has been executed on a real Windows desktop in this
  sandbox**: the download Save-as dialog, native JS dialogs, popup redirection,
  the persistent-profile restart cycle, the custom title bar/menus/fullscreen,
  and the single-instance message are **verified by API-surface inspection
  (javap against the jcef-17e805a jar) + compiling code + contract tests; not
  yet run on Windows**.
- The JCEF build genuinely lacks any permission-handler API (verified: no
  `org.cef` permission classes in the jar), so the File System Access API can
  never work in the webview — the fs bridge is the only sound design (see
  `decisions.md`).
- Heartbeat timings, 2h idle eviction and the 10-min sweeper are verified by
  code only (waiting 2h was out of scope).
- `pom.xml` artifact version is still 1.2.0 while the app reports 1.3.0 (see
  `README.md` version note).
