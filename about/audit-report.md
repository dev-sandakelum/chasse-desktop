# Audit Report — chasse-desktop (Java launcher)

Audit date: 2026-10-21 · Audited against HEAD `d07979552bcc117be44718c52fb4a7e35ffc0fd7` · Patch: `java-audit.patch` in the workspace root.

This is the report of the full audit + repair pass described in `about/`. The sibling web repo (`presentation-2`) has its own `about/audit-report.md` covering the web side; the bridge contract is identical in both.

## Follow-up — 2026-10-22

Finding: the web UI had implementations for `fs.chooseDeckFolder`,
`fs.confirmAddDecks`, `fs.relinkDeck`, and `fs.removeDeck`, but `DesktopBridge`
did not dispatch any of them. `FsService` now persists an atomic catalog beside
its config, accepts folders only from a Swing native chooser, issues expiring
server-side scan tokens for multi-deck confirmation, and treats removal as a
catalog/tombstone update that never touches deck files. The catalog grants file
access to linked folders outside the default root; removed default-root folders
are not automatically adopted again.

Verification: all production and JUnit test sources compile with
`javac --release 17`; the production compilation is clean. `mvn test` cannot
run here because Maven is unavailable. The web repository's full `npm run
lint` passes; its Bun-based bridge/E2E scripts and the native chooser still
need their respective runtime checks.

---

## 1. What was found

### 1.1 THE root cause of "the desktop app cannot save changes" (with evidence)

**The embedded webview ran with an in-memory browser profile.** `Main.java` never set `CefSettings.cache_path`, and:

- Decompiled `org.cef.CefSettings` from the shipped `jcefmaven-152.0.6.jar`: the constructor defaults `cache_path = null`.
- jcefmaven's `CefAppBuilder` uses `DEFAULT_CEF_SETTINGS.clone()` and adds no cache path (verified from its bytecode: the only directory it manages is `installDir`).
- CEF semantics: an empty `cache_path` means incognito-like **in-memory** storage — IndexedDB, localStorage and cookies do not survive the process.

The web app's storage ladder is desktop-folder → server (when writable) → **browser (IndexedDB fallback)**. In the embedded window every deck that landed in the browser store — including *all* decks until the user picks a folder, and every deck created by a pre-fs-bridge deployment — saved "successfully" (green "Saved" chip) and **vanished on the next app start**. That is precisely "cannot save changes".

**Fix (this audit, v1.3.0):** `cache_path = ~/.chasse-studio/browser-profile` + `root_cache_path = ~/.chasse-studio`, created before `builder.build()` — plus the mandatory companion: a **single-instance lock** (`AppInstanceGuard`, `FileLock` on `~/.chasse-studio/app.lock`), because two processes sharing one Chromium profile corrupt it and would also fight over port 8765.

**The second, design-level cause (evidence this was unavoidable):** the old save path relied on the browser's File System Access permission prompt ("Allow this site to edit files?"). The bundled JCEF (`jcef-17e805a+cef-152.0.6+chromium-152.0.7977.83`) **contains no permission-handler API whatsoever** — verified by enumerating every `org.cef` class in the jar: no `CefPermissionHandler`, no `CefPermissionCallback`. Without that API the prompt can never be shown or granted inside the webview, so any save flow depending on it was a dead end. The fs bridge — the Java side owning a real decks folder through a native chooser — is the correct replacement and is now finished, contract-matched and verified end-to-end.

### 1.2 Everything else found (grouped by area)

| Area | Finding | Resolution |
|---|---|---|
| Contract | `fs.chooseRoot {title,startPath,hint}` sent by the web app was **silently ignored** (Java called `fs.chooseRoot()` with no args) | Wired through: dialog title, start directory, hint in the grant confirmation |
| Downloads | No `CefDownloadHandler` → exports (.smdeck/.deck.tsx) did nothing visible in the embedded window | `DownloadBridge` (new): native Save-as (JFileChooser on the EDT, suggested name, remembered folder `downloads.dir`), throttled progress, `download` events pushed to the page for every stage, `openDownloadFolder` bridge action, cancel-dialog → `Continue("",false)` |
| JS dialogs | No `CefJSDialogHandler` → `alert()/confirm()/prompt()` and beforeunload confirmations silently vanish (calls resolve undefined) | `WindowDialogs` (new): native Swing dialogs on the EDT; blocking semantics preserved; beforeunload gets a real Yes/No. The web app replaced its own prompts with in-app dialogs in parallel — belt and braces |
| Popups | No `CefLifeSpanHandler` → `window.open`/`target="_blank"` produced unusable raw JCEF popup windows | `onBeforePopup` opens the URL in the **system browser** and cancels the popup |
| Single instance | Nothing prevented two launches — fatal once the profile is persistent | `AppInstanceGuard` file lock + friendly "already running" message (headless mode excluded) |
| Dead code | `remote.page-title` was parsed by AppConfig but consumed by nothing (both `RemotePage` and the page hardcode their titles) | Wired: `RemotePage.html(config.remotePageTitle())` substitutes the configured title |
| Versioning | `DesktopBridge.VERSION` 1.2.0 ↔ pom 1.2.0 while the fs capability advertised itself only as `["sync","fs"]` | Bumped to **1.3.0** everywhere; capabilities now `["sync","fs","downloads"]` so newer pages can detect download support |
| Release pipeline | A directly-published GitHub release fires BOTH `Release.yml` and `maven-publish.yml` → the Windows zip is built twice under two names | Documented in about/README.md + troubleshooting (deduplicating is a maintainer decision; both pipelines otherwise work) |
| Resource handling | Audited all streams/temps: try-with-resources everywhere, temps cleaned in `finally`, log ring bounded (200), rooms bounded (500 slides, 2h idle eviction), heartbeat on a single-thread scheduler, all executors daemon | No leak found; nothing to change |
| Security | Loopback server: Host check (127.0.0.1/localhost only), constant-time token compare (wrong token = 404 indistinguishable), Origin check when present, per-request path re-confinement, 256 MiB streaming cap, PNA preflight; `FsService` confinement: lexical segment validation (traversal, absolute, backslash, NUL, Windows reserved names, trailing dot/space, drive letters, 240-char cap) + canonical `toRealPath` containment via nearest existing ancestor + symlink write refusal + `NOFOLLOW_LINKS` scan | Verified by the new unit tests (below); no changes needed |
| Config | No way to persist anything except `app.url` | Generic `userProperty`/`saveUserProperty` helpers; `downloads.dir` uses them |

---

## 2. What was changed (file list and why)

| File | Change |
|---|---|
| `src/main/java/com/chasse/desktop/AppInstanceGuard.java` | **NEW** — single-instance file lock (acquire/release, second instance → message + exit) |
| `src/main/java/com/chasse/desktop/DownloadBridge.java` | **NEW** — `CefDownloadHandler`: native Save-as, `downloads.dir` persistence, throttled progress, `download` events (`started/saving/progress/completed/failed`), `openLastDownloadFolder()` (never arbitrary paths) |
| `src/main/java/com/chasse/desktop/WindowDialogs.java` | **NEW** — `CefJSDialogHandler` (alert/confirm/prompt/beforeunload → Swing on the EDT) + `CefLifeSpanHandler.onBeforePopup` → system browser |
| `src/main/java/com/chasse/desktop/Main.java` | single-instance guard; **persistent `cache_path`/`root_cache_path` (THE root-cause fix)**; wiring of `DownloadBridge` (+event sink), JS dialogs and popup handlers |
| `src/main/java/com/chasse/desktop/DesktopBridge.java` | VERSION 1.3.0; capabilities `+downloads`; `fs.chooseRoot` now honors `{title,startPath,hint}`; new `openDownloadFolder` action; `setDownloads` |
| `src/main/java/com/chasse/desktop/FsService.java` | `chooseRoot(title, startPath, hint)` (start path when it exists, hint in the grant text); test-injectable constructor (config-file path) |
| `src/main/java/com/chasse/desktop/AppConfig.java` | `downloadsDir()`/`saveDownloadsDir` + generic user-property helpers (refactored out of `saveUserAppUrl`) |
| `src/main/java/com/chasse/desktop/RemotePage.java` | `html(title)` applies the configured `remote.page-title` (was dead config) |
| `src/main/java/com/chasse/desktop/ServerManager.java` | serves `/remote` with the configured title |
| `pom.xml` | version 1.3.0; JUnit 5 (test scope) + surefire 3.2.5 |
| `src/test/java/com/chasse/desktop/FsServiceTest.java` | **NEW** — 21 unit tests: path confinement (traversal/absolute/backslash/NUL/empty/long), symlink write refusal + symlink-escape-via-missing-file refusal, create/read/write/delete/duplicate lifecycle, deck.json-last, no temp files left behind, 2 MiB limit, `FOLDER_NOT_SET`/`FOLDER_MISSING`/`ALREADY_EXISTS`/`NOT_FOUND`/`BAD_REQUEST` codes, scan correctness, hidden snapshot files |
| `about/*` | **NEW** — the six documentation files (README, architecture, storage, features, decisions, troubleshooting) |

Everything else in the repo was audited and deliberately left alone (smallest-change rule).

---

## 3. Test results and measurements

### 3.1 Automated + live tests (run after the final fixes)

| Check | Result |
|---|---|
| `mvn clean package` | **BUILD SUCCESS** (Java 21 toolchain, release target 17) |
| `FsServiceTest` (JUnit) | **21/21 PASS** — the security core and deck lifecycle of the fs capability |
| Headless sync server (real Javalin, live in this sandbox) | `/health` OK, `/remote` page served, `/api/status` diagnostics OK; **WS join with the QR token OK; bad token → `{"type":"error","message":"Invalid or expired link token"}`; unbound sessions cannot mutate state; nav/set-slide verified at the protocol level** |
| JCEF API surface (javap on the shipped jar) | `CefDownloadHandler`(+Adapter), `CefDownloadItem`, `CefBeforeDownloadCallback.Continue(path, showDialog)`, `CefJSDialogHandler`(+Adapter, `JSDialogType`, `CefJSDialogCallback.Continue(ok, input)`, `BoolRef`), `CefLifeSpanHandler.onBeforePopup`, `CefClient.add{Download,JSDialog,LifeSpan}Handler` — **all present**; permission-handler classes — **absent** (the evidence for §1.1) |
| Web-side counterpart suites (sibling repo) | bridge-contract smoke **35/35** (a JS re-implementation of this repo's `FsService` contract against the real web store), desktop-mode E2E **16/16** (real app in Chromium with mocked `cefQuery`: detection, deck creation through `fs.createDeck` on disk, save queue, reload persistence, PromptDialog) |

### 3.2 What the numbers say (and what they cannot)

- The web-side heap/listener measurements (26-slide deck, 12 open/close cycles: listeners and DOM nodes return to baseline each cycle → no leak) live in the sibling repo's report — that is where the webapp memory lives even inside this launcher's window.
- **JVM + Chromium process memory on real Windows was NOT measured** (this sandbox has no display and no Windows natives). The packaging scripts keep `-Xmx512m`; Chromium manages its own memory. Marked unverified in `about/features.md`.
- Startup time on Windows was not measured (first run downloads ~100 MB of Chromium natives — dominated by network).

---

## 4. Not fixed, assumptions, unverified items

1. **Windows GUI behaviors** (the persistent profile actually surviving restarts, the Save-as dialog, native JS dialogs, popups→system-browser, the single-instance message) are implemented against JCEF APIs that are **confirmed present in this exact build**, and the protocol around them is contract-tested — but they were **not executed on a real Windows desktop** in this sandbox. A Windows smoke run is the remaining step before release.
2. The **JavaFX/Swing `JFileChooser`** Save-as cannot pre-select the download in a headless environment — pickSavePath's extension-restore and name sanitation logic is unit-reasoned, not UI-tested.
3. `hello` answering any page is deliberate (capability discovery = the same info as `getBridgeInfo`); the fs surface stays origin-gated. Assumption documented in decisions.md.
4. The **duplicate release workflow** (Windows zip built twice with two names on a published release) is documented, not changed — deduplicating is a release-pipeline decision.
5. The **3-second `Runtime.halt(0)` exit guard** is kept as-is (CEF can hang on dispose); it trades a clean profile flush for never-zombie processes. With the profile now persistent, an unflushed profile is recoverable by Chromium; documented in troubleshooting.
6. `-Xmx512m` was left unchanged (works today; raising it without Windows measurement would be guessing).
7. `AppInstanceGuard` failures fall back to "let the app run" (a guard must never break the app); the sync server still reports a clear port-in-use error for the rare conflicting case.

---

## 5. The final bridge contract (both repos must match)

Full tables in `about/architecture.md` of both repos. Summary:

- **Transport:** one `window.cefQuery`. **v1** `{id, action, payload}` → `{id, ok:true, data}` | `{id, ok:false, error}`; **v2** `{cmd, ...params}` → raw shape or `{code, message}`.
- **v1 actions:** `getBridgeInfo`, `getServerStatus`, `getServerLog`, `startServer`, `stopServer`, `restartServer`, `selectAddress`, `joinRoom`, `setSlide`, `nav`, `reportSteps`, `syncDeck`, `setNotes`, `setUi`, `ping`, `openDownloadFolder`.
- **v2 commands:** `hello` → `{app:"chasse-desktop", version:"1.3.0", capabilities:["sync","fs","downloads"]}`, `fs.getRoot`, `fs.chooseRoot{title?,startPath?,hint?}`, `fs.listDecks`, `fs.readText`, `fs.writeText` (atomic), `fs.createDeck` (deck.json last), `fs.deleteDeck` (verified gone), `fs.duplicateDeck`, `fs.listFiles`, `fs.delete`, `fs.fileUrl` (loopback-only).
- **Error codes:** `FOLDER_NOT_SET, FOLDER_MISSING, NO_OS_PERMISSION, NOT_FOUND, TOO_LARGE, IO_ERROR, ALREADY_EXISTS, BAD_REQUEST, BUSY, CANCELLED, FORBIDDEN, UNKNOWN_CMD`.
- **Pushes:** `server-status`, `server-log`, `sync-state`, `sync-presence`, `sync-token`, `download {type, stage: started|saving|progress|completed|failed, id, fileName, path?, error?, percent?, receivedBytes?, totalBytes?}` — all trust-gated.
- **Trust:** `originOf(app.url) === originOf(currentUrl)`; only `getBridgeInfo`/`hello` answer untrusted pages.

---

## 6. How to apply the patches

From the workspace root (`chasse-workspace/`):

```bash
git -C nextjs-project/presentation-2 apply ../../nextjs-audit.patch
git -C java-project/chasse-desktop    apply  ../../java-audit.patch
```

`java-audit.patch` was produced against the original HEAD `d079795` with a clean working tree and `--binary`, after confirming `.gitignore` covers `target/`. It was validated by `git apply --check` + apply + full build (`mvn clean package`, tests included) on a **fresh clone of that HEAD**.

After applying: `mvn clean package` (runs FsServiceTest), then build the installer with `scripts/package-windows.ps1`. **Deploy the web repo** (the sibling patch) so the page uses the new contract surface (`downloads` capability, chooseRoot params, download toasts).
