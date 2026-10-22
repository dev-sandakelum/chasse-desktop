# Chassé Studio Desktop — Overview

**Version 1.3.0** (bridge protocol and app version; Maven artifact is still 1.2.0 — bump `pom.xml` before the next tagged release).

## What it is

Chassé Studio Desktop is the native shell for the hosted Chassé Studio web app
(`https://studio-chasse.vercel.app/` by default). It is a single-process
**Java 17 + Swing** application with three pillars:

1. **Embedded Chromium webview** (JCEF via `jcefmaven`) in an undecorated
   window with a fully custom dark title bar (`#1e1b29`), `</>`-badge logo,
   `File / View / Help` menus and `Ctrl+F11` fullscreen.
2. **LAN sync server** (Javalin, HTTP + WebSocket on one port, default
   `0.0.0.0:8765`) that serves a self-contained phone remote page and lets any
   phone on the same Wi-Fi drive the presentation (token-authenticated).
3. **fs bridge** — the app owns a user-chosen decks folder on disk and exposes
   every file operation to the web app through the Desktop Bridge Protocol v2
   (`fs.*` commands over `window.cefQuery`), plus a loopback-only HTTP file
   server for assets / uploads.

The web app URL is loaded inside the webview; this repo contains **no bundled
web code**. The bridge contract is the only coupling between the two repos.

## Requirements

| | Detail |
|---|---|
| JDK | 17+ (release target 17; Temurin 21 verified) |
| Maven | 3.6+ |
| First run | Downloads ~100 MB of Chromium natives into `~/.chasse-studio/jcef-bundle` (one-time; requires internet; corporate proxies can block this) |
| Offline after first run | The webview needs the hosted web app URL reachable; phone remote is fully local |

## How to run from source

```bash
# Build (skipping tests for speed; drop -DskipTests to run them)
mvn -DskipTests package

# Run
java -cp "target/package/*" com.chasse.desktop.Main
```

`mvn package` places the runnable jar **and all runtime dependencies** into
`target/package/` (the jpackage input directory and the complete classpath).

### Headless / diagnostic mode (no GUI, no JCEF)

```bash
java -cp "target/package/*" com.chasse.desktop.Main --headless --room=demo
```

Runs only the sync server, creates a demo room and prints the phone join link to
stdout. The instance lock is skipped in headless mode.

## How to run the tests

```bash
mvn test
```

Runs `FsServiceTest` — 21 JUnit 5 unit tests covering path confinement, symlink
refusal, deck lifecycle, atomic writes, limits and all error codes.

## CLI flags

| Flag | Effect |
|---|---|
| `--app-url=<url>` | Override the hosted web app URL |
| `--port=<n>` | Override the sync server port (default 8765) |
| `--room=<deckId>` | Headless mode: create a demo room and print the join link |
| `--headless` | Server-only mode (no GUI, no JCEF, no instance lock) |
| `--osr` | Off-screen rendering (headless Linux / CI) |
| `--devtools=<port>` | Chromium remote debugging at `http://127.0.0.1:<port>` |
| `--cef-arg=<switch>` | Extra Chromium/JCEF switch (repeatable) |

Both `--key=value` and `--key value` forms are accepted.

## Configuration layering (later wins)

1. Bundled `src/main/resources/app.properties`
2. User file `~/.chasse-studio/app.properties`
3. CLI flags

The user file also stores `downloads.dir` (written after each Save-as).

## How to build installers

| Platform | Script | Output |
|---|---|---|
| Windows | `scripts/package-windows.ps1` | `target\installer\ChasseStudio\ChasseStudio.exe` |
| macOS | `scripts/package-macos.sh` (auto-detects arm/x64) | `target/installer/*.dmg` |
| Linux | `scripts/package-linux.sh` | `target/installer/*.deb` |

All scripts call `mvn package -P<platform>` (to bundle the Chromium natives
offline) then `jpackage --type app-image|dmg|deb`. Requires JDK 14+.

## How to release

Pushing a release tag to GitHub fires two workflows:
- **`maven-publish.yml`** (`on: release: [created]`) — Windows zip, Linux deb,
  two macOS dmgs.
- **`Release.yml`** (`on: release: [published]`) — Windows zip only.

Both fire on the same publish event, producing a duplicate Windows zip under
two slightly different names. This is a known quirk; consolidate before the
next release (see `ARCHITECTURE.md` → CI pipeline section).

## How the two repos relate

The desktop app loads the hosted web app URL. The **only coupling** is the
bridge contract (`CONTRACT.md`). The web app detects the bridge via
`typeof window.cefQuery === "function"` and calls `{cmd:"hello"}` to discover
capabilities. An old desktop build without `"fs"` triggers an "update
available" notice on the web side while falling back to browser storage — no
hard failure.

## Useful paths at runtime

| Path | Contents |
|---|---|
| `~/.chasse-studio/app.properties` | User config: `app.url`, `downloads.dir`, etc. |
| `~/.chasse-studio/config.json` | Chosen decks folder + confirmed grant |
| `~/.chasse-studio/app.lock` | Single-instance file lock (released on exit, even on crash) |
| `~/.chasse-studio/jcef-bundle/` | Chromium natives install cache |
| `~/.chasse-studio/browser-profile/` | Persistent Chromium profile (IndexedDB, localStorage, cookies) — **new in 1.3.0** |

See `STORAGE.md` for the full inventory.
