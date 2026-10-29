# Testing — chasse-desktop

## Automated tests

### `mvn test` — JUnit 5 (`FsServiceTest`)

21 unit tests covering the security core and deck lifecycle of the `FsService`
(fs capability). Run with:

```bash
mvn test
# or
mvn clean package   # tests run as part of the package phase
```

**Coverage:**

| Category | Tests |
|---|---|
| Path confinement | Traversal (`../../etc/passwd`), absolute paths (`/etc/passwd`), backslash (`..\\evil`), NUL bytes, empty segments, `.`/`..` segments |
| Windows-specific | Reserved device names (`CON`, `NUL`, `COM1`, `LPT3`), trailing dot/space, drive references (`C:`) |
| Symlink write refusal | Symlink target at the write path; missing file whose nearest existing ancestor is a symlink that escapes the root |
| Deck lifecycle | `createDeck` → `listDecks` → `readText` → `writeText` → `deleteDeck`; `duplicateDeck` (patched deck.json id/title/modified) |
| Atomicity | `deck.json` written **LAST** in `createDeck` and `duplicateDeck`; no temp files left behind after success or failure |
| Limits | 2 MiB text limit (`TOO_LARGE`); no file size enforcement in `createDeck` beyond 2 MiB per file |
| Error codes | `FOLDER_NOT_SET`, `FOLDER_MISSING`, `ALREADY_EXISTS`, `NOT_FOUND`, `BAD_REQUEST` |
| Scan correctness | `listDecks` returns only folders that contain `deck.json` matching the deck-id pattern |
| Hidden snapshot files | `.stagesmith/snapshots/` contents are not mistaken for decks |
| `.git` inside a deck | `deleteDeck` removes read-only git object files; `duplicateDeck` never copies `.git` |

### `GitServiceTest` — the `git` capability (needs git on PATH, skipped otherwise)

Runs against a real git on real disk: a deck folder initialised as a
repository plus a bare `origin.git` in the temp dir. Covers: plain deck →
`{connected:false, reason:"no-repo"}`; repository on `main` → connected, dirty
until committed, dirty again after a new file; another branch / detached HEAD
→ not connected; the generated commit message format; `publish` commits and
pushes to origin (remote has the commit, upstream set, second publish is a
clean no-op); `publish` without an origin commits locally and explains;
`publish` on a plain deck → `NOT_CONNECTED` and never creates a repository.

**Audit result:** 21/21 PASS, `mvn package` BUILD SUCCESS.

### Bridge contract smoke test (web repo)

`scripts/test-bridge-contract.mjs` in the web repo drives the real web-side
desktop store/fs modules against a Java-contract mock on real disk —
35 assertions. See the web repo's `TESTING.md` for details.

### Playwright E2E (web repo)

`scripts/e2e-desktop-mock.mjs` in the web repo runs the real app in Chromium
with a mocked `cefQuery` + a full Java mock on real disk — 16 assertions.

### Headless sync server (live)

Run the sync server headless to verify HTTP/WS routes:

```bash
java -cp "target/package/*" com.chasse.desktop.Main --headless --room=demo
# Output: Phone remote link: http://127.0.0.1:8765/remote?deck=demo&t=<token>
```

Manual checks at `http://127.0.0.1:8765`:

| Check | Expected |
|---|---|
| `GET /health` | `{"ok":true,"status":"running"}` |
| `GET /remote` | Self-contained phone HTML, dark theme |
| `GET /api/status` | JSON with status/port/addresses/rooms/phones |
| WebSocket join with valid token | `{"type":"joined","state":{…}}` |
| WebSocket join with bad token | `{"type":"error","message":"Invalid or expired link token"}` |
| `nav`/`set-slide` before join | No state mutation |

**Audit result:** all checks PASS (worklog `java-fixes`).

---

## Desktop-mode manual checklist

This checklist must be run on a real Windows desktop before each release. Items
marked **UI** require a display; items marked **API** can be verified headless
with `--devtools`.

### First run and startup

- [ ] First launch downloads ~100 MB of Chromium natives (console shows
  `jcefmaven` progress); subsequent starts skip the download.
- [ ] If `app.url` is blank, the first-run URL prompt appears.
- [ ] Window opens: undecorated dark title bar, `</>` badge, "Chassé — slides
  written as code", File/View/Help menus, minimize/maximize/close buttons.
- [ ] Minimum window size (980×640) is enforced — the window cannot be
  smaller.
- [ ] Custom title bar drag-to-move works; double-click toggles maximized
  state.
- [ ] `Ctrl+F11` toggles fullscreen; title bar hides in fullscreen.
- [ ] Second launch: "Chassé Studio is already running" message appears and the
  second process exits.

### fs bridge / folder operations

- [ ] Library shows a "Choose folder" banner on first run (no decks folder set).
- [ ] Clicking "Choose folder" opens a native folder chooser; selecting a valid
  folder shows the confirmation dialog ("Allow Chassé Studio to read and write
  decks in this folder?").
- [ ] After confirming, the library lists decks from the chosen folder.
- [ ] `fs.getRoot` returns `{configured: true, path: "…", valid: true}`.
- [ ] `FOLDER_MISSING` banner appears when the chosen folder is renamed/deleted
  while the app is running; "Choose folder" re-picks and recovers.
- [ ] **New deck** creates a folder under the root with `deck.json` written
  last.
- [ ] **Edit and autosave**: type in a slide, wait 600 ms; `deck.json` and
  `slides/<id>.tsx` are updated on disk.
- [ ] **Ctrl+S** forces an immediate save.
- [ ] **Reload** (File → Reload or refresh): changes survive (persistent
  profile).
- [ ] **Restart the app**: changes survive (this is the root-cause regression
  test for the save bug — the deck must still be in the library and its content
  must match the last save).
- [ ] **Duplicate deck**: creates a copy folder with a patched `deck.json`.
- [ ] **Delete deck**: removes the folder; confirmed dialog must say "Remove
  from library" (see DECISIONS.md Task 9).

### Downloads (1.3.0)

- [ ] Export `.smdeck`: native Save-as dialog opens with the suggested name and
  the remembered folder; saving completes and a "Saved to \<path\>" toast
  appears with an "Open folder" button.
- [ ] "Open folder" opens Windows Explorer at the saved file's folder.
- [ ] Cancelling the Save-as dialog shows "The download was cancelled" toast
  (no crash).
- [ ] Export `.deck.tsx` follows the same flow.
- [ ] Download progress toast appears for large exports (> a few seconds).

### JS dialogs and popups (1.3.0)

- [ ] No JS `alert()` / `confirm()` / `prompt()` call silently drops — the
  Swing dialog appears.
- [ ] `beforeunload` dialog (navigate away with unsaved changes): native Yes/No
  appears.
- [ ] A link with `target="_blank"` opens in the system browser (not a raw JCEF
  popup).

### Phone remote (on a real device)

- [ ] QR panel → Start → server starts (status becomes "running").
- [ ] QR code is displayed with a valid LAN URL (not a loopback or virtual
  adapter address).
- [ ] Scanning the QR on a phone in the same Wi-Fi loads the remote page and
  shows "Live".
- [ ] Prev/Next on the phone navigates the desktop presentation.
- [ ] Fullscreen button on the phone enters/exits fullscreen on the desktop.
- [ ] Stop → phone page shows "Reconnecting" then disconnects cleanly.
- [ ] After stopping and restarting, rescanning the QR works.

### Trust gate

- [ ] `getBridgeInfo` returns `{trusted: true}` when the loaded URL matches
  `app.url` origin.
- [ ] `fs.*` commands return `FORBIDDEN` when loaded from a different origin.
- [ ] Push events (`server-status`, `sync-state`, etc.) are NOT dispatched to
  an untrusted page.

---

## Memory verification (Task 4)

Run `scripts/memory-measure.mjs` in the web repo with the app served inside a
real Chromium (not headless) and record:

- Baseline JS heap before any deck is opened
- Heap after opening and closing the same 26-slide deck 20 times
- Net delta after 20 cycles (must be < 20 MB from baseline)

See the web repo's `AUDIT_REPORT.md` for reference numbers from the last audit
(12 cycles, 26-slide deck, listeners/DOM nodes return to baseline each cycle).

For the Java process, use `jcmd <pid> VM.native_memory` or VisualVM before and
after 20 open/close cycles and record the heap and non-heap totals.

---

## What the automated tests do NOT cover

- GUI rendering on a real Windows display
- Native Save-as dialog behavior
- Native folder chooser behavior
- Persistent profile restart cycle (the single most important regression: create
  a deck → restart the app → the deck must still be there)
- Real phone on real Wi-Fi
- Windows paths with spaces, non-English characters, long paths (> 200 chars),
  locked files, antivirus interference
- DPI scaling (high-DPI / 4K display)
- First-run Chromium download progress

These are all marked **UI** in the checklist above and must be verified manually
before each release.
