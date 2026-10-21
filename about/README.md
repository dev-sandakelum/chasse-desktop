# Chassé Studio Desktop — README (about docs)

**Version 1.3.0** (bridge protocol + app version; see the version note at the end)

## What this is

Chassé Studio Desktop is the native shell for the hosted Chassé Studio web app
(slides written as code, default `https://studio-chasse.vercel.app/`). It is a
single-process Java 17 + Swing application with three pillars:

1. **Embedded Chromium webview** (JCEF via `jcefmaven`) in an undecorated window
   with a fully custom dark title bar, File/View/Help menus and `Ctrl+F11`
   fullscreen.
2. **LAN sync server** (Javalin, HTTP + WebSocket on one port, default
   `0.0.0.0:8765`) that serves a self-contained phone remote page and lets any
   phone on the same Wi-Fi drive the presentation (token-authenticated).
3. **fs bridge** — the desktop app owns a user-chosen **decks folder** on disk
   and exposes every file operation to the web app through the Desktop Bridge
   Protocol v2 (`fs.*` commands), plus a loopback-only file server for
   assets/uploads. This is the durable replacement for the web app's browser
   storage fallback.

This `/about` folder documents the current state after the 1.3.0 audit + fix
pass. See `architecture.md` for the module map and the full bridge contract,
`storage.md` for every file on disk, `features.md` for the feature/status
matrix, `decisions.md` for the why behind each design choice, and
`troubleshooting.md` for common errors.

## Requirements

| Requirement | Detail |
|---|---|
| JDK | 17+ (release target is 17; the audit build used Temurin 21 — any 17+ JDK works) |
| Maven | 3.6+ |
| First run | Downloads ~100 MB of Chromium natives into `~/.chasse-studio/jcef-bundle` (one-time; corporate proxies can block this) |
| Offline after that | The webview needs the web app URL reachable; the phone remote itself is fully local |

Bundling the natives into the build (no first-run download) is done with the
per-platform Maven profiles: `-Pwindows`, `-Pmacos-arm`, `-Pmacos-intel`,
`-Plinux`.

## Run from source

```bash
mvn -DskipTests package
java -cp "target/package/*" com.chasse.desktop.Main
```

The build copies the runnable jar **and** all runtime dependencies into
`target/package/` (maven-jar-plugin + maven-dependency-plugin), which doubles
as the complete runtime classpath and the single `jpackage` input directory.

### Headless / diagnostic mode

```bash
java -cp "target/package/*" com.chasse.desktop.Main --headless --room=demo
```

Runs only the sync server (no GUI, no JCEF, no instance lock), creates a demo
room and prints the phone join link to stdout. Verified live in the audit
(worklog `java-fixes`): `/health`, `/remote`, `/api/status`, WS join with a
valid token, bad-token rejection, and unbound sessions unable to mutate state.

## Tests

```bash
mvn test
```

Runs `FsServiceTest` — **21 unit tests** covering path confinement, symlink
refusal, deck lifecycle (create/read/write/delete/duplicate), atomicity
(`deck.json` written last, no temp files left behind), limits and the contract
error codes. Audit result: **21/21 pass**, `mvn package` BUILD SUCCESS.

The bridge transport itself is not unit-tested on the Java side (it needs a live
`CefClient`); the web repo drives the same contract from the other side with a
35-assertion contract mock + a 16-assertion Playwright E2E against a full Java
mock (worklog `web-fixes`: 35/35 and 16/16 pass).

## CLI flags

| Flag | Effect |
|---|---|
| `--app-url=<url>` | Override the hosted web app URL |
| `--port=<n>` | Override the sync server port (default 8765) |
| `--room=<deckId>` | Headless diagnostic mode: create this demo room and print a join link |
| `--headless` | Server-only mode, no GUI/webview |
| `--osr` | Off-screen-rendering webview (headless Linux / CI) |
| `--devtools=<port>` | Chromium remote debugging on `http://127.0.0.1:<port>` |
| `--cef-arg=<switch>` | Extra Chromium/JCEF switch, repeatable (e.g. `--cef-arg=--no-sandbox`) |

Both `--key=value` and `--key value` forms are accepted.

## Configuration layering (later wins)

1. **Bundled defaults** — `src/main/resources/app.properties`
   (`app.url=https://studio-chasse.vercel.app/`, `server.port=8765`,
   `network.preferred-prefix=10.161.79.`, `remote.page-title=…`)
2. **User file** — `~/.chasse-studio/app.properties`
   (also stores `downloads.dir`, written automatically after each Save-as)
3. **CLI flags** — highest priority

A blank `app.url` (only reachable if the user clears it) triggers the first-run
URL prompt. The loaded page's origin must match `app.url`'s origin, or the
bridge answers "not trusted" and refuses all fs commands (see
`troubleshooting.md`).

## Installers

| Platform | Script | Result |
|---|---|---|
| Windows | `scripts/package-windows.ps1` | `mvn -DskipTests package -Pwindows` → `jpackage --type app-image` → `target\installer\ChasseStudio\` (runnable `ChasseStudio.exe`; the zip step lives in CI, not in the script) |
| macOS | `scripts/package-macos.sh` | auto-detects arm/x64 → `-Pmacos-arm` / `-Pmacos-intel` → `jpackage --type dmg` |
| Linux | `scripts/package-linux.sh` | `-Plinux` → `jpackage --type deb` |

All scripts resolve the version from `pom.xml` (`help:evaluate`) and need
`jpackage` (JDK 14+, 17 recommended).

## GitHub Actions release pipelines

- **`Release.yml`** — `on: release: [published]` → Windows only: runs
  `package-windows.ps1`, zips the app-image as
  `ChasseStudio-<ref-name-without-v>-windows.zip`, uploads to the release.
- **`maven-publish.yml`** — `on: release: [created]` → Windows zip
  (`ChasseStudio-<full-ref-name>-windows.zip`), Linux `.deb`, and two macOS
  DMGs (arm64 via `macos-latest`, x64 via `macos-13`), all uploaded to the
  release.

**Known duplicate (verified in the workflow files):** publishing a release
fires **both** `created` and `published`, so both workflows run and the
Windows zip is built twice under two names — one strips the leading `v` from
the tag, the other keeps it. Harmless but wasteful; consolidate the triggers
(e.g. keep only `maven-publish.yml`, or split the triggers) before the next
release.

## Version note

The app/bridge version is **1.3.0** (`DesktopBridge.VERSION` — advertised by
`hello` and the About dialog). The Maven artifact version in `pom.xml` is
**still 1.2.0**, so CI-built artifacts are currently named with the old
version — bump `pom.xml` `<version>` before the next tagged release.
