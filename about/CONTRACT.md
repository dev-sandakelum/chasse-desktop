# Chassé Studio — Web ↔ Java Bridge Contract

Version **1.5.0** · Java-side bridge contract. The web integration copy lives
at `presentation-2/about/CONTRACT.md`.

---

## Transport

One `window.cefQuery` (JCEF `CefMessageRouter`, `persistent: false`).
Every query is answered **exactly once** (errors also travel via the success
callback, never via `onFailure`). Two protocols share the same channel,
distinguished by the presence of a `cmd` key.

---

## Protocol v1 — Sync Actions

Request shape: `{id, action, payload?}`

Success response: `{id, ok: true, data: { … }}`

Failure response: `{id, ok: false, error: "<message>"}`

`id` is echoed verbatim (may be any JSON value; null when not set).

`getBridgeInfo` is the only v1 action that answers untrusted pages. All other
actions return `{ok: false, error: "not trusted"}` when the page origin does
not match `app.url`.

| Action | Payload fields | `data` fields | Notes |
|---|---|---|---|
| `getBridgeInfo` | — | `{trusted, version, platform, appUrl}` | Available to any page |
| `getServerStatus` | — | `{status, port, addresses[], selectedAddress, error}` | `status`: `stopped\|starting\|running\|error` |
| `getServerLog` | — | `{lines: [{ts, level, text}]}` | Ring buffer (last 200); live updates via `server-log` events |
| `startServer` | — | `{ok: true}` | Async; status arrives via `server-status` event |
| `stopServer` | — | `{ok: true}` | Async; status arrives via `server-status` event |
| `restartServer` | — | `{ok: true}` | Async |
| `selectAddress` | `{address}` | `{selectedAddress}` | Only accepts addresses in the current list |
| `joinRoom` | `{deckId, role?, slides, slideId?, step?}` | `{state, token}` | role: `"display"\|"editor"`; pushes `sync-token` event when room is created; fresh rooms honor `slideId`/`step` resume hint |
| `setSlide` | `{deckId, slideId, step}` | `{ok: true}` | Returns `error: "unknown room"` if room does not exist |
| `nav` | `{deckId, dir?}` | `{ok: true}` | `dir`: `"next"\|"prev"` (default `"next"`) |
| `reportSteps` | `{deckId, slideId, steps}` | `{ok: true}` | Steps clamped to 1..50 |
| `syncDeck` | `{deckId, slides}` | `{ok: true}` | Re-sync slide order after add/delete/reorder |
| `setNotes` | `{deckId, slideId, note}` | `{ok: true}` | Note capped at 8000 chars; U+2028/U+2029 stripped |
| `setUi` | `{deckId, qr?, overview?}` | `{ok: true}` | Relayed to the room's phones as `{"type":"ui"}` |
| `ping` | — | `{t}` | `t`: server timestamp ms |
| `toggleFullscreen` | — | `{ok: true}` | Toggles the app window's fullscreen (the webview cannot use the browser Fullscreen API; used by Present mode) |
| `openDownloadFolder` | — | `{ok: boolean}` | Reveals the folder of the last completed download; **takes no path** |

`slides` in `joinRoom`/`syncDeck` shape: `[{id: string, label: string}]` (MAX 500).

`state` object (canonical room state):
```json
{
  "deckId": "...",
  "slideId": "...",
  "index": 0,
  "step": 0,
  "steps": 1,
  "order": [{ "id": "...", "label": "..." }],
  "note": "...",
  "updatedAt": 1700000000000
}
```

`addresses[]` item: `{address, iface, display, virtual}`.

---

## Protocol v2 — hello + fs Commands

Request shape: `{cmd, ...params}`

Success response: **raw contract shape** (no `ok` wrapper).

Failure response: `{code: "<CODE>", message: "<human text>"}`.

The web client distinguishes errors purely by the presence of `{code, message}`.
Never both a success payload and an error in the same response.

`hello` is answered for **any** page (capability discovery, same information
level as `getBridgeInfo`). All `fs.*` and `git.*` commands require the trusted
app origin and return `{code: "FORBIDDEN", message: "…"}` otherwise.

### Commands

| Command | Params | Success shape | Notes |
|---|---|---|---|
| `hello` | — | `{app: "chasse-desktop", version: "1.7.1", capabilities: ["sync","fs","downloads","assetUrls","git"]}` | Available to untrusted pages |
| `fs.getRoot` | — | `{configured: bool, path: string, valid: bool}` | Never throws; it is the health report |
| `fs.chooseRoot` | `{title?, startPath?, hint?}` | `{cancelled: true}` OR `{configured, path, valid}` | Cancel is a clean answer, not an error; shows native folder chooser on Swing EDT |
| `fs.listDecks` | — | `[{id, title, folder, slides, modified}]` | Sorted by modified descending |
| `fs.readText` | `{deck, path}` | `{text: string}` | ≤ 2 MiB |
| `fs.writeText` | `{deck, path, text}` | `{ok: true}` | Atomic: temp file → fsync → rename; refuses symlink targets |
| `fs.createDeck` | `{deck, files: {path → text}}` | `{ok: true, id: string}` | `deck.json` written **LAST**; ≤ 500 files; `deck.json` is required |
| `fs.deleteDeck` | `{deck}` | `{ok: true}` | Success only after the folder is verified gone |
| `fs.chooseDeckFolder` | `{title?, startPath?}` | `{cancelled:true}` \| `{ok:true, added:[deck]}` \| `{alreadyInLibrary:true}` \| `{needsConfirm:true, candidates:[candidate]}` | Native folder chooser; the page supplies no path |
| `fs.confirmAddDecks` | `{tokens:[token]}` | `{ok:true, added:[deck]}` | Tokens are issued by the immediately preceding folder scan and expire after five minutes |
| `fs.relinkDeck` | `{id, title?, startPath?}` | Same as `fs.chooseDeckFolder` | Replaces only catalog entry `id`; never creates a second entry |
| `fs.removeDeck` | `{id}` | `{ok:true, filesKept:true, path}` | Removes the catalog entry and permission only; never deletes or changes deck files |
| `fs.duplicateDeck` | `{deck, copyId, title?}` | `{ok: true, id: string}` | Patches `deck.json` (id/title/modified); deck.json written last |
| `fs.listFiles` | `{deck, dir?}` | `[{name, directory, size, modified}]` | One directory level; `modified` is epoch ms |
| `fs.delete` | `{deck, path}` | `{ok: true}` | File or folder (recursive); verified gone |
| `fs.readAssetBase64` | `{deck, path}` | `{base64}` | Compatibility fallback only; images up to 8 MiB |
| `fs.beginAssetUpload` | `{deck, path}` | `{uploadId}` | Creates a same-folder temporary upload |
| `fs.writeAssetChunk` | `{uploadId, base64}` | `{size}` | Appends one bounded chunk; total upload limit 256 MiB |
| `fs.finishAssetUpload` | `{uploadId}` | `{ok:true, size}` | Atomically renames the completed temporary file |
| `fs.abortAssetUpload` | `{uploadId}` | `{ok:true}` | Closes and deletes the temporary file |
| `git.status` | `{deck}` | `{connected:true, branch:"main", dirty:bool}` OR `{connected:false, reason}` | Connected **only** when the deck folder itself is a git repository (`<deck>/.git`) checked out on `main`; `reason` ∈ `no-repo` / `branch` (+`branch` name) / `detached` / `git-missing`. `dirty` = modified or untracked files present. Never creates a repository |
| `git.publish` | `{deck}` | `{ok:bool, committed:bool, commit?:string, error?:string}` | `git add -A` · `git commit -m "chasse: update <yyyy-MM-dd HH:mm:ss>"` (message generated by the app — the page cannot supply one) · `git push -u origin main`. Git outcomes (no remote, auth, rejected, offline, identity missing, git missing) come back as `{ok:false, error}` — `committed` says whether the local commit happened anyway. `NOT_CONNECTED` error for a deck `git.status` reports as not connected. One publish runs at a time |

| `ui.theme` | `{theme: "dark"\|"light", panel?, panel2?, pop?, line?, ink?, ink2?, mut?}` | `{ok: true}` | Sent BY the desktop app's own injected script (`Main.THEME_SYNC_JS`, after every main-frame load and on every `data-ss-theme` change) — the page reports its chrome theme and computed `--ss-*` colours so the native title bar can match the web toolbar. One-way; colours are CSS strings as `getComputedStyle` returned them; unparsable ones fall back to the built-in palette. Trusted origin only |

Git runs as a child process with a fixed argv (no shell) in the deck folder
`FsService` resolved — the same confinement as every fs command. Every prompt
is disabled (`GIT_TERMINAL_PROMPT=0`, `GCM_INTERACTIVE=Never`), so the user must
have signed in to the remote once in a terminal; Java starts the child with
`CREATE_NO_WINDOW`, so no console ever flashes.

### Error Codes

| Code | Meaning |
|---|---|
| `FOLDER_NOT_SET` | No decks folder has been chosen yet |
| `FOLDER_MISSING` | The chosen folder no longer exists / is not a directory |
| `NO_OS_PERMISSION` | OS-level permission denied (read-only folder, locked file, etc.) |
| `NOT_FOUND` | Deck or file does not exist |
| `TOO_LARGE` | Text file > 2 MiB or upload > 256 MiB |
| `IO_ERROR` | General I/O failure |
| `ALREADY_EXISTS` | Deck or copy id already exists |
| `BAD_REQUEST` | Invalid deck id, path traversal attempt, reserved name, etc. |
| `BUSY` | Only one native chooser may be open at a time |
| `NO_DECK_JSON` | Chosen folder and its immediate child folders contain no usable `deck.json` |
| `NOT_READABLE` | Chosen folder cannot be inspected by the desktop app |
| `CANCELLED` | User cancelled the native dialog |
| `FORBIDDEN` | Origin not trusted for fs / git commands |
| `UNKNOWN_CMD` | Unrecognized `cmd` value |
| `NOT_CONNECTED` | `git.publish` on a deck whose folder is not a repository on `main` |

### Path rules enforced by Java (`FsService.checkRelPath`)

- Must be relative (no leading `/`)
- No `..`, `.`, or empty segments
- No `\` or NUL characters
- ≤ 240 characters total; ≤ 255 per segment
- On Windows: no reserved device names (`CON`, `NUL`, `COM1`–`COM9`,
  `LPT1`–`LPT9`, `CONIN$`, `CONOUT$`), no trailing `.` or space, no drive
  references (`C:`)
- Canonical `toRealPath()` containment check after lexical validation (symlinks
  and junctions that escape the deck folder are rejected)
- Symlink write targets (`fs.writeText`, HTTP PUT) are refused outright

## Same-origin asset interception (`assetUrls`)

When `hello.capabilities` includes `assetUrls`, desktop assets use the virtual
same-origin path `/__chasse_files__/<encoded-deck-id>/<encoded-relative-path>`.
JCEF's `CefRequestHandler.getResourceRequestHandler()` and
`CefResourceRequestHandler.getResourceHandler()` intercept this prefix before
network loading. Other paths pass through untouched.

Only frames whose URL origin matches the request origin and is either the
configured hosted app origin or localhost/127.0.0.1:3000 are served. Java
resolves the deck through the catalog and re-confines each decoded relative
path with `FsService`. File reads are chunked on a worker pool; responses
include MIME type, Content-Length, ETag, Last-Modified and single-range support.
Every intercepted request logs path, status, bytes and elapsed milliseconds.

Every response (including 304 and errors) carries
`Access-Control-Allow-Origin: *`, exactly like the web route `/api/assets`.
The sandboxed stage runtime is an opaque-origin iframe: plain `<img>` loads
need no CORS, but the thumbnail capture inside it `fetch()`es the deck's
images to inline them into the snapshot, which is a CORS read. The header
exposes nothing new — the virtual path only exists inside the app's own
window and every path is confined to an allowed deck folder.

HTTP statuses: 200/206 success, 304 unchanged, 403 untrusted origin or unsafe
path, 404 missing deck/file, 405 unsupported method, 416 invalid range, 500
unexpected I/O. Error responses include `X-Chasse-Error-Code`: `FORBIDDEN`,
`BAD_REQUEST`, `NOT_FOUND`, or `IO_ERROR`.

Uploads use `fs.beginAssetUpload`, `fs.writeAssetChunk`, and
`fs.finishAssetUpload`; chunks are streamed into a temporary file and the final
path is atomically renamed after revalidation. The web client sends at most
256 KiB per command; total upload limit is 256 MiB. `fs.readAssetBase64` supports
the compatibility image fallback up to 8 MiB. No file listener or custom
scheme is used for assets.

---

## Java → Web Push Events

Java calls:
```js
window.__chasseDesktop && window.__chasseDesktop._handleEvent(<json-string>)
```

The JSON is passed as a **string literal** (Gson-escaped), so the page
receives exactly one string argument and calls `JSON.parse` on it. The hook
also accepts a raw object for backward compatibility with older web builds
(v1.1 protocol).

Events are delivered **only** when the current page origin matches the trusted
app origin (`isTrusted()`).

| Event type | Shape | When |
|---|---|---|
| `server-status` | `{type, status, port, addresses[], selectedAddress, error}` | Every LAN-server state change (including network changes) |
| `server-log` | `{type, ts, level, text}` | Every log line appended to the ring buffer |
| `sync-state` | `{type, deckId, state}` | Room state changes (nav, setSlide, syncDeck, reportSteps, setNotes) |
| `sync-presence` | `{type, deckId, remotes}` | Phone count changes |
| `sync-token` | `{type, deckId, token}` | Pushed once when a new room is created (fires from `joinRoom`) |
| `download` | `{type:"download", stage, id, fileName, path?, error?, percent?, receivedBytes?, totalBytes?}` | Each stage of a native download (desktop ≥ 1.3.0) |

`download.stage` values: `"started"` → `"saving"` → `"progress"` (throttled to ≤ 4/s) → `"completed"` (with `path`) or `"failed"` (with `error`).

---

## Trust Model

```
isTrusted() = originOf(app.url) === originOf(currentUrl)
```

`currentUrl` is maintained by the CEF display handler's `onAddressChange`.
Default ports are normalized (HTTP→80, HTTPS→443). A blank `app.url` is
**never** trusted.

- **Untrusted pages:** only `getBridgeInfo` (v1) and `hello` (v2) are answered.
  All other v1 actions return `{ok: false, error: "not trusted"}`. All `fs.*`
  and `git.*` commands return `{code: "FORBIDDEN", message: "…"}`. Push events
  are suppressed.
- **Trusted pages:** full access to all commands and events.

---

## Deck ID Format

`^[a-z0-9][a-z0-9-]{0,63}$` — used as a folder name; must match this pattern
for all bridge commands that take a `deck` or `deckId` field.

---

## Compatibility and Capability Detection

```js
// 1. detect the bridge
const hasBridge = typeof window.cefQuery === "function";

// 2. capability discovery
const hello = await cefCmd({ cmd: "hello" });
const hasFs = hello.capabilities?.includes("fs");
const hasDownloads = hello.capabilities?.includes("downloads");
const hasGit = hello.capabilities?.includes("git"); // editor git button (desktop decks only)

// 3. fallback
// No bridge → browser / server store (unchanged web behavior)
// Bridge but no "fs" → browser store + "Desktop app update available" notice
// Bridge with "fs" → desktop store; check fs.getRoot for configured+valid
```

`hello` always answers any page (capability discovery = same info level as
`getBridgeInfo`). An older build that does not advertise `"fs"` or `"downloads"`
must be handled gracefully — the web app shows an update notice and falls
back to the browser store without breaking.

---

## Phone Remote WebSocket Sub-Protocol (Javalin `/ws`)

The phone connects to `ws://<lanAddress>:<port>/ws` with the `deck` and `t`
(token) query params embedded in the QR URL. Messages are JSON text frames.

### Phone → Server

| type | Fields | Requires join? | Effect |
|---|---|---|---|
| `join` | `{type, deckId, token, label}` | — | Token-checked; binds the session to the room; server replies with `joined` |
| `nav` | `{type, dir}` | yes | `"next"\|"prev"` |
| `set-slide` | `{type, slideId, step}` | yes | Jumps to a specific slide+step |
| `get-state` | `{type}` | yes | Server replies with `state` |
| `ping` | `{type, t}` | no | Server replies with `pong` |
| `pong` | `{type, t}` | no | Heartbeat reply |
| `fullscreen` | `{type, action}` | yes | `"toggle"\|"enter"\|"exit"` → forwarded to the desktop window |

### Server → Phone

| type | Fields | Meaning |
|---|---|---|
| `joined` | `{type, state}` | Join accepted; state is the current room state object |
| `state` | `{type, state}` | State update (broadcast to all phones in the room) |
| `presence` | `{type, remotes}` | Phone count changed |
| `ui` | `{type, qr?, overview?}` | QR / overview panel state relay |
| `ping` | `{type, t}` | Server heartbeat (every 5s) |
| `pong` | `{type, t}` | Reply to a phone ping |
| `bye` | `{type, reason}` | Server stopping; `reason: "server-stopping"` |
| `error` | `{type, message}` | Join failure: `"Unknown deck session"` or `"Invalid or expired link token"`; session closed after |

Heartbeat: server pings every **5 s**; phones silent for **15 s** are closed.
The phone page reconnects with 1.5 s / 3 s / 5 s backoff (consecutive failed
attempts) and immediately on `visibilitychange`.

---

## Versioning

- Bridge protocol version `1.5.0` is advertised by `hello.version` and
  `getBridgeInfo.version`.
- The web app must handle older builds gracefully (see Compatibility section
  above).
- When adding a new command or action, bump the version in both repos and
  update both integration contracts in the same change.
- Error codes must never be renamed (add new ones, keep old ones).
