# Troubleshooting

Symptom → cause → fix. Paths assume `~` = the user home;
everything config-related lives under `~/.chasse-studio/`.

## App & window

| Symptom | Cause | Fix |
|---|---|---|
| A second launch shows "Chassé Studio is already running" and exits | **By design** — `AppInstanceGuard` holds a `FileLock` on `~/.chasse-studio/app.lock` (the persistent Chromium profile must have one owner, and two sync servers would fight over 8765) | Use the existing window (check the taskbar). The lock is released by the OS when the first instance exits — even on crash — so there is **no stale-lock recovery to perform** |
| "Port 8765 is already in use — close the other program or change server.port" | The port probe found the port taken (another program, or a zombie Java process) | Close the other program, or set `server.port=<other>` in `~/.chasse-studio/app.properties` / `--port=` |
| Blank window / long first start | First run downloads ~100 MB of Chromium natives into `~/.chasse-studio/jcef-bundle` (console progress) | Wait / check the console. **Corporate proxies can block the download** — pre-bundle natives by building with `-Pwindows` (see `scripts/`) or allow the download host. The bundle is a cache: deleting it forces a re-download but breaks nothing |
| "Bridge not trusted" in the QR panel; `fs.*` returns `FORBIDDEN` | The loaded page origin ≠ the origin of the configured `app.url` (the display handler's `onAddressChange` feeds the trust check) | Use **File → Change app URL…** or edit `app.url` in `~/.chasse-studio/app.properties` so the origin matches the actually-loaded URL exactly (scheme/host/port, default ports normalized) |
| App updated but the page still behaves old | Stale webview cache | File → Reload, or delete `~/.chasse-studio/browser-profile` (see profile recovery below) |
| Version mismatch in artifacts | `pom.xml` is still 1.2.0 while the app reports 1.3.0 | Bump `pom.xml` `<version>` before the next tagged release (CI names artifacts from it) |

## Saving & the decks folder

| Symptom | Cause | Fix |
|---|---|---|
| `FOLDER_MISSING` — "The decks folder no longer exists" | The chosen folder was deleted/renamed/unmounted | The web app shows a banner — choose the folder again (`fs.chooseRoot`). The web app falls back to browser storage meanwhile |
| `NO_OS_PERMISSION` — "no permission to …" | Folder/file is read-only or locked (OneDrive **placeholder folders can be read-only**; files open in another program; antivirus locks) | Pick a real local folder (not a synced placeholder dir), close the program holding the file. `fs.chooseRoot` again if the whole folder is affected |
| `IO_ERROR` during a save; the page retries and eventually succeeds | Antivirus/backup software briefly locking files during the atomic temp-file+rename write | Nothing to do — writes fail cleanly with `IO_ERROR` and the web app's save queue retries (1s/3s/10s backoff, web-repo behavior); the old file is never corrupted. If it persists, exclude the decks folder from real-time scanning |
| `TOO_LARGE` on `fs.writeText` | Text files are capped at 2 MiB (whole-file JSON strings) | Split large assets; upload big binaries through the loopback server's PUT (cap 256 MiB) via `fs.fileUrl` |
| Paths with >240 chars rejected | Per-contract limit (`BAD_REQUEST`); segments >255 also rejected, plus Windows reserved names (`CON`, `COM1`…), trailing dot/space, `X:` drive refs | Use shorter folder/file names inside decks |
| `ALREADY_EXISTS` on create/duplicate | A deck folder with that id already exists under the root | Pick another id (deck ids: `^[a-z0-9][a-z0-9-]{0,63}$`) |
| Profile corruption / webview storage glitch (site data odd, login gone) | Chromium profile under `~/.chasse-studio/browser-profile` corrupted | **Delete `~/.chasse-studio/browser-profile` while the app is closed.** You lose the embedded browser's IndexedDB/localStorage/cookies — **NOT the decks folder** (decks live in the user-chosen folder on disk) |
| "Desktop app update available" notice in the web app | The bridge exists but the installed build lacks the `fs` capability (pre-1.3.0 build) | Install the 1.3.0 build |

## Downloads (1.3.0)

| Symptom | Cause | Fix |
|---|---|---|
| Save-as always opens the same folder | That's `downloads.dir` — remembered after every download (`~/.chasse-studio/app.properties`) | Just navigate elsewhere; the new folder is remembered automatically (never cleared, only replaced) |
| "The download was cancelled" toast you didn't trigger | The Save-as dialog was dismissed — the CEF contract cancel is `Continue("", false)` | Re-run the export |
| "Open folder" does nothing | No download completed this session and no `downloads.dir` yet | Complete a download first |

## Phone remote

| Symptom | Cause | Fix |
|---|---|---|
| Phone can't connect after scanning QR | (a) not the same network; (b) **client isolation on 10.x campus/guest Wi-Fi** blocks device-to-device traffic; (c) firewall blocking TCP 8765; (d) QR built from a virtual adapter (WSL/Hyper-V/VirtualBox/VPN) — these are auto-de-prioritized but selectable; (e) the token died with the app | Use the phone's hotspot or a network without client isolation; allow inbound **TCP 8765** (HTTP + WebSocket — no UDP involved); pick the physical adapter in the QR panel; restart the server and rescan. Diagnostics: open `http://<lan-ip>:8765/api/status` in the phone's browser — if that loads, the server side is fine |
| Phone shows "This link has expired" | Token invalid/unknown room — typically the desktop app was restarted (rooms + tokens are **in-memory by design**) | Reopen the QR panel in the web app and scan the fresh code |
| Phone drops to "Reconnecting" then recovers | Wi-Fi idle/lock — the page reconnects with 1.5s/3s/5s backoff and immediately on visibility change; the server pings every 5s and closes after 15s of silence | Nothing — expected. If it never recovers, see "can't connect" above |
| Desktop doesn't fullscreen when the phone asks | No GUI listener (headless mode — phone fullscreen is a no-op there) or an old build | Run GUI mode / update |

## Diagnostics & maintenance

| Task | How |
|---|---|
| Inspect the embedded webview | `--devtools=9222` → Chromium DevTools at `http://127.0.0.1:9222` |
| Run the sync server without a GUI (CI/servers) | `--headless` (add `--room=demo` to get a ready phone link on stdout) |
| Server log | QR panel in the web app (`getServerLog` + `server-log` events), or stdout in headless mode |
| Clean the JCEF install cache | Delete `~/.chasse-studio/jcef-bundle` (safe; re-downloaded on next start) |
| Reset all settings | Delete `~/.chasse-studio/app.properties` (config) and `config.json` (decks folder choice + grant) — the app then asks again. Keep the decks folder itself |
| Reset everything except decks | Delete the whole `~/.chasse-studio/` except leave the user-chosen decks folder untouched (it lives elsewhere by definition) |

## Known pipeline quirk

Publishing a GitHub release fires **both** workflows (`Release.yml` on
`published`, `maven-publish.yml` on `created`), so the Windows zip is built
twice under two names (`ChasseStudio-<ver>-windows.zip` vs
`ChasseStudio-v<ver>-windows.zip`). Harmless — but consolidate before the next
release (see `README.md`).
