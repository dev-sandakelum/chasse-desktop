# Decisions and why

Every entry states the decision, the reason, and — where relevant — the
alternative it replaced. Root-cause findings cite the audit worklog
(`audit-phase-0` / `java-fixes` / `web-fixes`).

## 1. Persistent browser profile (1.3.0) — THE save-bug fix

**Decision:** set `CefSettings.cache_path` to
`~/.chasse-studio/browser-profile` (and `root_cache_path` to
`~/.chasse-studio`) instead of leaving them null.

**Why:** jcefmaven's `CefSettings.cache_path` defaults to null, which makes CEF
run with an **in-memory profile** (incognito-like). The web app's browser-store
fallback (IndexedDB/localStorage) was therefore wiped on every app restart —
the root cause of "the desktop app cannot save". Verified against the actual
jar (`CefSettings.cache_path` default null; `Main.java` never set it before).

**Replaced:** nothing — it was a missing setting, found by the audit.

## 2. Single-instance `FileLock` (`AppInstanceGuard`)

**Decision:** exclusive `FileLock` on `~/.chasse-studio/app.lock`, acquired in
GUI mode before JCEF starts; a second instance shows a message and exits.

**Why:**
- Required once the profile is persistent — a Chromium profile must have
  exactly one owner; two writers corrupt IndexedDB/localStorage.
- Also prevents two sync servers from fighting over port 8765.
- `FileLock` is released by the OS on exit, even on crash — no stale-lock
  recovery logic needed. If the lock cannot be taken for an unexpected IO
  reason the app still runs (a guard must never break the app; the port probe
  still reports a clear port-in-use error).

**Replaced:** nothing — new in 1.3.0, a direct consequence of decision 1.

## 3. Java owns the decks folder (the fs capability)

**Decision:** the desktop app takes ownership of ONE user-chosen folder
(native chooser + explicit YES/NO grant) and exposes file ops over the bridge;
the web app uses it instead of the File System Access API when embedded.

**Why:** JCEF (jcef-17e805a, Chromium 152) has **no permission-handler API**
(verified: no `org.cef` permission classes in the jar). The browser's
"Allow this site to edit files?" prompt can therefore **never appear** in the
webview — File System Access was a dead end there. A native folder chooser +
`confirmedRoot` is durable consent that never silently expires (unlike browser
grants) and works identically across restarts.

**Replaced:** relying on the web app's File System Access API in the webview
(impossible) and, before that, the browser-store fallback (data loss — see
decision 1).

## 4. Native Save-as via `CefDownloadHandler`

**Decision:** implement `CefDownloadHandler.onBeforeDownload` → Swing
`JFileChooser` Save-as on the EDT, then `Continue(path, false)`; push
started/saving/progress/completed/failed events to the page.

**Why:** verified the JCEF build has `CefDownloadHandler` and
`CefClient.addDownloadHandler`; JCEF shows no download UI by default, so
exports silently disappeared in the desktop app. Native dialog + native
downloader gives resumable, cancelable, OS-integrated downloads with the
user's real folder choice.

**Rejected alternative:** the page streams file chunks through the bridge —
more protocol surface, no resumability, no native dialog, and it would still
need a Save-as somewhere.

## 5. `onBeforePopup` → system browser

**Decision:** cancel JCEF popups (`window.open`, `target=_blank`) and open the
URL in the user's system browser.

**Why:** JCEF's default popup is a raw browser window with no usable chrome and
no history — worse than the system browser. (Verified the
`CefLifeSpanHandler`/`onBeforePopup` API exists in the jar.)

## 6. Native JS dialogs as a safety net

**Decision:** implement `CefJSDialogHandler` (alert/confirm/prompt) and
`onBeforeUnloadDialog` with Swing dialogs on the EDT, preserving blocking
semantics.

**Why:** JCEF never renders JS dialogs unless a handler exists — calls
silently return undefined. Meanwhile the web app **also** replaced its own
`window.prompt` usages with in-app dialogs (worklog `web-fixes`). Belt and
braces: if any `alert`/`prompt`/beforeunload survives on the web side, the
desktop app still shows a real dialog. Zero cost, strictly better than a
silently dropped dialog.

## 7. `fs.chooseRoot` params honored (`title`/`startPath`/`hint`)

**Decision:** pass the web app's dialog copy through — dialog title, chooser
start directory (when it exists), and the one-line hint inside the grant
confirmation.

**Why:** parity with the web dialog's wording — before this audit the params
were parsed-but-ignored on the Java side, a silent contract mismatch (flagged
in `audit-phase-0`, fixed in `java-fixes`).

## 8. `openDownloadFolder` takes NO path

**Decision:** the bridge action reveals the folder of the **last completed
download** (or the remembered downloads folder) — the page cannot specify a
target.

**Why:** never let a web page open arbitrary paths in the file manager. The
action exists purely as a toast affordance ("Saved to X → Open folder").

## 9. Loopback-only file server (never the LAN port)

**Decision:** deck files/assets/uploads are served by a separate
`com.sun.net.httpserver` listener bound to `127.0.0.1` on a **random** port
with a 48-hex per-session token in the URL; the 0.0.0.0:8765 Javalin server
serves only the phone page/health/status/ws.

**Why:** the phone remote never gains any file access; the page gets real URLs
(works for `<img>`/fetch/PUT uploads) instead of base64 blobs tunneled through
`cefQuery` (rejected: huge JSON strings, no streaming, no Range, no caching).
Loopback + Host/Origin checks + constant-time token + per-request re-confinement
= five independent layers (DNS-rebinding, cross-origin page abuse, and direct
LAN access are all covered).

## 10. Atomic writes + `deck.json` written last

**Decision:** every write is temp-file-in-same-folder → flush → `FD.sync()` →
`ATOMIC_MOVE` rename (with fallback); `createDeck`/`duplicateDeck` write
`deck.json` LAST; failed creates are cleaned up.

**Why:** crash safety. A crash midway leaves the old file intact plus a stray
`.chasse-tmp-*` — never a half-written file. An unfinished deck folder without
`deck.json` never looks like a deck (the web app's folder-is-project
convention). Same-folder temp files make the rename genuinely atomic
(same volume).

## 11. `confirmedRoot` — remember granted folders

**Decision:** persist the canonicalized folder the user already granted;
the YES/NO "Allow Chassé Studio to read and write decks in this folder?"
appears only for NEW folders.

**Why:** native consent dialogs cannot be faked by pages (they are shown by
Swing, from a trusted code path), but re-asking on every start would be
hostile. One explicit grant, remembered in `~/.chasse-studio/config.json`,
re-validated (exists/readable/writable) before every operation.

## 12. `Continue("", false)` to cancel a download

**Decision:** cancelling the Save-as dialog (or a dialog failure) calls
`callback.Continue("", false)` — never just drops the callback.

**Why:** that is the CEF contract for "cancel this download"; dropping the
callback would leave CEF waiting. Also guarantees every query/callback is
answered exactly once (the same principle as the bridge always answering
`onQuery`).

## 13. Daemon cached-thread pools + the 3s halt guard

**Decision:** the bridge worker (server lifecycle), fs worker (file IO +
native dialogs) and the loopback server executor are daemon cached pools; the
shutdown path keeps its daemon `exit-guard` thread that force-halts the JVM
after 3 s.

**Why:** file IO and modal dialogs must never block the CEF UI thread, and the
app must still be able to exit fast if CEF hangs on dispose. Usage is bounded
by the origin check + fixed command whitelist (an untrusted page can only
reach `hello`, not spawn fs work). Headless mode needs the JVM to exit on
Ctrl+C, so daemon threads everywhere.

## 14. `remote.page-title` actually wired

**Decision:** `/remote` now serves the page with the configured title
substituted.

**Why:** it was parsed-but-unused dead config before this pass — a config key
that silently did nothing. Fixed while touching the config layer anyway
(`downloads.dir`).

## 15. JUnit tests for `FsService` only

**Decision:** unit tests cover the fs layer (21 tests); no unit tests for the
bridge dispatch, server or phone handler.

**Why:** bridge dispatch needs a live `CefClient`/`CefMessageRouter` —
untestable headless in this setup; the web repo's 35-assertion contract mock
drives the same transport from the other side, and the headless server was
verified live instead. Testing the pure file layer where the security core
lives is the highest-value use of JUnit here.

## 16. JCEF natives: download on first run vs bundled profiles

**Decision:** the default build downloads natives (~100 MB) into
`~/.chasse-studio/jcef-bundle` on first run; installer builds activate
`-Pwindows` / `-Pmacos-arm` / `-Pmacos-intel` / `-Plinux` to bundle them.

**Why:** keeps the dev build trivial and the jar small, while end-user
installers work fully offline. The install dir is a cache — safe to delete,
re-downloaded.
