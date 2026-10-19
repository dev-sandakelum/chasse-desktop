# Chassé Studio Desktop

> **The desktop companion for [Chassé Studio](https://studio-chasse.vercel.app/) — slides written as code.**

Chassé Studio Desktop wraps the hosted web app in a native window and adds two things the hosted site can never have on its own: a **local LAN sync server** that lets a phone act as a wireless slide remote, and a proper **desktop experience** with a custom title bar, keyboard shortcuts, and fullscreen support.

[![Release](https://img.shields.io/github/v/release/dev-sandakelum/chasse-desktop?style=flat-square)](https://github.com/dev-sandakelum/chasse-desktop/releases)
[![Java 17](https://img.shields.io/badge/Java-17-blue?style=flat-square)](https://adoptium.net/)
[![License](https://img.shields.io/github/license/dev-sandakelum/chasse-desktop?style=flat-square)](LICENSE)

---

## What is Chassé Studio?

[Chassé Studio](https://studio-chasse.vercel.app/) is a web-based presentation tool where slides are written as code. The source is hosted at [github.com/dev-sandakelum/presentation-2web](https://github.com/dev-sandakelum/presentation-2web).

This desktop app is the **native shell** for that web app — it gives you:

| Feature | Details |
|---|---|
| Native window | Custom dark title bar, drag-to-move, minimize / maximize / close |
| Embedded browser | Chromium via JCEF — the full web app, no browser chrome |
| Phone remote | LAN sync server lets any phone on the same Wi-Fi control slides |
| Fullscreen | `Ctrl+F11` — also triggerable from the phone remote |
| Offline-capable | Once the web app loads, the remote works without internet |

---

## Architecture

```
 ┌────────────────────────────────────────────────────────────┐
 │  Chassé Studio Desktop  (Java 17 + Swing + JCEF)          │
 │                                                            │
 │  ┌─────────────────────┐  CefMessageRouter  ┌──────────┐  │
 │  │  Embedded webview   │ ◄────────────────► │  Bridge  │  │
 │  │  (JCEF / Chromium)  │                    │  (Java)  │  │
 │  │                     │  pushed events ──► │          │  │
 │  │  studio-chasse      │                    └────┬─────┘  │
 │  │  .vercel.app        │                         │        │
 │  └─────────────────────┘                    ┌────▼─────┐  │
 │                                             │  Javalin │  │
 │                                             │  :8765   │  │
 │                                             │  /remote │  │
 │                                             │  /ws     │  │
 │                                             └────┬─────┘  │
 └──────────────────────────────────────────────────┼────────┘
                                                    │ LAN
                                                    ▼
                                           📱  Phone browser
                                           http://<lan-ip>:8765
                                           /remote?deck=…&t=…
```

The web app talks to Java through `window.cefQuery()` (Transport A). The phone connects over WebSocket (Transport B) — same port as HTTP, token-authenticated.

---

## Download

Pre-built installers are on the [Releases page](https://github.com/dev-sandakelum/chasse-desktop/releases).

| Platform | File |
|---|---|
| Windows (x64) | `ChasseStudio-vX.X.X-windows.zip` → extract and run `ChasseStudio.exe` |
| macOS (Apple Silicon) | `ChasseStudio-vX.X.X-macos-arm64.dmg` |
| macOS (Intel) | `ChasseStudio-vX.X.X-macos-x64.dmg` |
| Linux (Debian/Ubuntu) | `ChasseStudio-vX.X.X-linux.deb` |

> **First launch only:** the app downloads the Chromium engine (~100 MB) into `~/.chasse-studio/jcef-bundle`. This is a one-time operation — subsequent starts are instant.

---

## Getting Started

1. **Install** — grab the installer for your OS from the Releases page.
2. **Launch** — on first start you'll be asked for the web app URL. Enter `https://studio-chasse.vercel.app/` (or your own self-hosted instance).
3. **Open the QR panel** inside the web app and click **Start** to bring up the LAN sync server.
4. **Scan the QR code** with your phone — it connects as a wireless slide remote.
5. Use **Prev / Next** on the phone, or swipe, to control slides.

---

## Build from Source

### Requirements

- JDK 17+ (`java --version`)
- Apache Maven 3.6+ (`mvn --version`)

### Compile & run

```bash
mvn package
java -cp "target/package/*" com.chasse.desktop.Main
```

### Create installers

```bash
# Windows
powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1

# macOS (auto-detects Apple Silicon vs Intel)
chmod +x scripts/package-macos.sh && ./scripts/package-macos.sh

# Linux (produces a .deb)
chmod +x scripts/package-linux.sh && ./scripts/package-linux.sh
```

Each script:
1. Runs `mvn package` with the matching platform profile (bundles JCEF natives so end users don't need a separate download)
2. Runs `jpackage` (requires JDK 14+) to produce a self-contained installer

### Headless / diagnostic mode

Run the sync server only — no GUI, useful for testing the phone remote in isolation:

```bash
java -cp "target/package/*" com.chasse.desktop.Main --headless --room=demo
```

Prints a phone join link to stdout:
```
  Phone remote link:
    http://192.168.x.x:8765/remote?deck=demo&t=<token>
```

---

## Configuration

Settings are resolved in priority order: **CLI flags → user file → bundled defaults**.

**Bundled defaults** (`src/main/resources/app.properties`):

```properties
app.url=                          # hosted web app URL — first-run prompt if blank
server.port=8765                  # LAN sync server port
network.preferred-prefix=10.161.79.  # preferred LAN subnet for auto interface selection
```

**User config file:** `~/.chasse-studio/app.properties` (created automatically, wins over bundled defaults)

**CLI flags:**

| Flag | Description |
|---|---|
| `--app-url=https://…` | Override the web app URL |
| `--port=9000` | Override the sync server port |
| `--headless` | Server-only mode, no GUI |
| `--room=<id>` | Create a diagnostic demo room and print the join link |
| `--devtools=9222` | Enable Chromium DevTools on `http://127.0.0.1:9222` |
| `--cef-arg=<switch>` | Pass extra Chromium flags (repeatable) |
| `--osr` | Off-screen rendering mode (for headless Linux / CI) |

---

## Phone Remote

The phone remote is a **fully self-contained HTML page** served by the embedded Javalin server. It requires no internet — everything is bundled in the jar.

**Flow:**
1. Open the QR panel in the web app → Start the server → scan the QR code.
2. The phone opens `http://<your-ip>:8765/remote?deck=…&t=…` in its browser.
3. The page connects over WebSocket, receives the current deck state, and shows Prev / Next controls.
4. Swipe left/right on the phone also navigates slides.
5. Speaker notes for the current slide are shown on the phone.

**Network requirements:**
- Phone and laptop must be on the **same network** (same Wi-Fi or one sharing the other's hotspot).
- Campus networks with **client isolation** block device-to-device traffic — use a phone hotspot instead.
- Allow inbound TCP on port 8765 through your firewall (Windows Defender prompts automatically; on Linux: `sudo ufw allow 8765/tcp`).

---

## Security

- **Origin trust** — the Java bridge only accepts calls from the exact origin of the configured `app.url`. Any other page gets `{"ok":false,"error":"not trusted"}`.
- **Token-gated phone access** — each room mints a cryptographically random 12-hex token. The QR link contains it. Restarting the app mints new tokens; old QR links become invalid.
- **Fixed action whitelist** — the page can only invoke the documented bridge actions, never arbitrary code.
- **No CORS** — the phone remote page and its WebSocket endpoint are same-origin by construction.
- **LAN only** — the server binds `0.0.0.0` but only serves the static remote page, health JSON, and the token-checked WebSocket. No file system access of any kind.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Blank window on first launch | JCEF natives are downloading (~100 MB) — watch the console. Check your internet connection. |
| "Port 8765 is already in use" | Another process holds the port. Close it or set `server.port=<other>` in `~/.chasse-studio/app.properties`. |
| "Bridge not trusted" in QR panel | The loaded page origin doesn't match `app.url`. Use *File → Change app URL…* to set the correct origin. |
| Phone can't connect after scanning | Same network? Client isolation? Firewall blocking 8765? Try `http://<your-ip>:8765/api/status` from the phone browser for diagnostics. |
| QR link expired on phone | The app was restarted — tokens are in-memory only. Reopen the QR panel and scan the new code. |
| Multiple network interfaces | Use the interface picker in the QR panel to select the address the phone can reach. |

---

## Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Ctrl+F11` | Toggle fullscreen |

The phone remote also responds to swipe gestures (left = next, right = prev).

---

## Related

- **Web app source:** [github.com/dev-sandakelum/presentation-2web](https://github.com/dev-sandakelum/presentation-2web)
- **Live web app:** [studio-chasse.vercel.app](https://studio-chasse.vercel.app/)
- **Desktop releases:** [github.com/dev-sandakelum/chasse-desktop/releases](https://github.com/dev-sandakelum/chasse-desktop/releases)

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| GUI | Swing (undecorated, fully custom title bar) |
| Browser engine | JCEF (Java Chromium Embedded Framework) via jcefmaven |
| HTTP + WebSocket server | Javalin 6 |
| JSON | Gson |
| Build | Apache Maven |
| Packaging | jpackage (JDK 14+) |
| CI / Releases | GitHub Actions |

---

## License

See [LICENSE](LICENSE) for details.
