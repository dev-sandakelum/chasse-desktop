package com.chasse.desktop;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefKeyboardHandler;
import org.cef.handler.CefKeyboardHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.misc.BoolRef;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;

/**
 * Chassé Studio Desktop — entry point.
 *
 * <p>The window is undecorated; the OS title bar is replaced by {@link TitleBar}
 * — a minimal 32 px bar (logo, File/View/Help, the page title, thin window
 * controls) painted in the web app's own chrome palette ({@link BarTheme}) so it
 * continues the web toolbar beneath it. The page reports its theme and live
 * toolbar colours through the {@code ui.theme} bridge command ({@link
 * #THEME_SYNC_JS} is injected after every load, no change to the web app), and
 * the last reported theme is remembered so the next launch starts in it.
 * {@link ResizeBorder} gives the undecorated window its resize handles.</p>
 */
public final class Main {

    public static final String APP_NAME    = "Chassé";
    public static final String APP_VERSION = DesktopBridge.VERSION;

    /**
     * Injected into the page after every main-frame load: reports the chrome
     * theme ({@code html[data-ss-theme]}) and the computed toolbar colours
     * ({@code --ss-panel} …) once now and again on every theme toggle (a
     * MutationObserver on that attribute). Nothing in the web app changes —
     * the page merely answers what it already renders.
     */
    static final String THEME_SYNC_JS =
            "(function(){"
            + "if(window.__chasseBarTheme)return;window.__chasseBarTheme=true;"
            + "var d=document.documentElement;"
            + "function v(n){try{return(getComputedStyle(d).getPropertyValue(n)||'').trim();}catch(e){return'';}}"
            + "function send(){try{"
            + "var t=d.dataset.ssTheme==='light'?'light':'dark';"
            + "var r={cmd:'ui.theme',theme:t,panel:v('--ss-panel'),panel2:v('--ss-panel2'),pop:v('--ss-pop'),"
            + "line:v('--ss-line'),lineStrong:v('--ss-line-strong'),ink:v('--ss-ink'),ink2:v('--ss-ink2'),"
            + "mut:v('--ss-mut'),accSoft:v('--ss-acc-soft')};"
            + "if(typeof window.cefQuery==='function')window.cefQuery({request:JSON.stringify(r),persistent:false,"
            + "onSuccess:function(){},onFailure:function(){}});"
            + "}catch(e){}}"
            + "send();"
            + "try{new MutationObserver(function(){send();}).observe(d,{attributes:true,attributeFilter:['data-ss-theme']});}catch(e){}"
            + "})();";

    // ── fullscreen state ──────────────────────────────────────────────────────
    // (read from the CEF key thread too → volatile; everything else runs on the EDT)
    private static volatile boolean fullscreen  = false;
    private static int       prevExtendedState  = Frame.NORMAL;
    private static Rectangle prevBounds         = null;
    /** the floating "Exit full screen" pill shown while in fullscreen (EDT only) */
    private static JWindow           fsHint      = null;
    private static javax.swing.Timer fsHintTimer = null;
    /** debounces the keyboard toggle (the Swing dispatcher and the CEF key handler can both see one press) */
    private static long lastKeyToggleNanos = 0;

    // CEF key event constants (cef_event_flags / Windows virtual-key codes)
    private static final int CEF_CONTROL_DOWN = 1 << 2;
    private static final int CEF_ALT_DOWN     = 1 << 3;
    private static final int VK_ESCAPE        = 27;
    private static final int VK_F11           = 122;

    private Main() {}

    // =========================================================================
    // entry point
    // =========================================================================

    public static void main(String[] args) {
        AppConfig config;
        try {
            config = AppConfig.load(args);
        } catch (Exception e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }
        // one app instance at a time: the persistent browser profile and the
        // sync-server port must never be shared by two processes
        if (!config.headless() && !AppInstanceGuard.acquire()) {
            System.err.println("Chass\u00e9 Studio is already running — showing a message and exiting.");
            try {
                JOptionPane.showMessageDialog(null,
                        "Chass\u00e9 Studio is already running.\n\nUse the existing window (check your taskbar).",
                        "Chass\u00e9 Studio", JOptionPane.INFORMATION_MESSAGE);
            } catch (Throwable ignored) {
            }
            System.exit(0);
            return;
        }
        if (config.headless() || config.room() != null) {
            SyncRooms rooms = new SyncRooms();
            ServerManager servers = new ServerManager(config, rooms);
            runHeadless(config, rooms, servers);
            return;
        }
        runGui(config);
    }

    // =========================================================================
    // headless / diagnostic mode
    // =========================================================================

    private static void runHeadless(AppConfig config, SyncRooms rooms, ServerManager servers) {
        System.out.println("Chassé Studio Desktop — headless mode (no GUI, no webview)");
        servers.addListener(new ServerManager.Listener() {
            @Override public void onStatus(String status, int port,
                    List<LanAddresses.LanAddress> addresses, String selectedAddress, String error) {
                System.out.println("[server] status: " + status + (error == null ? "" : " — " + error));
            }
            @Override public void onLog(long ts, String level, String text) {
                System.out.println("[server:" + level + "] " + text);
            }
        });
        servers.log("info", "Desktop app started (headless mode)");
        if (config.room() != null) {
            List<SyncRooms.SlideRef> demo = List.of(
                    new SyncRooms.SlideRef("demo-1", "Demo slide 1 — Welcome"),
                    new SyncRooms.SlideRef("demo-2", "Demo slide 2 — Phone remote works"),
                    new SyncRooms.SlideRef("demo-3", "Demo slide 3 — Prev / Next / notes"),
                    new SyncRooms.SlideRef("demo-4", "Demo slide 4 — Swipe too"),
                    new SyncRooms.SlideRef("demo-5", "Demo slide 5 — The end"));
            SyncRooms.JoinResult join = rooms.join(config.room(), demo);
            rooms.setNotes(config.room(), "demo-3", "Notes are just text.\nThey support line breaks.");
            servers.start();
            String link = "http://" + servers.bestAddress() + ":" + config.port()
                    + "/remote?deck=" + config.room() + "&t=" + join.token;
            System.out.println("\n  Phone remote link:\n    " + link + "\n");
            servers.log("info", "Room '" + config.room() + "' ready — join link: " + link);
        } else {
            servers.start();
        }
        System.out.println("Press Ctrl+C to stop.");
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { servers.stop(); } catch (Exception ignored) {}
            done.countDown();
        }));
        try { done.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    // =========================================================================
    // GUI mode
    // =========================================================================

    /** Decoded once, off the EDT, while the rest of startup proceeds. */
    private static final class StartupAssets {
        final List<Image> windowIcons;
        final Image badgeLogo;
        StartupAssets(List<Image> windowIcons, Image badgeLogo) {
            this.windowIcons = windowIcons;
            this.badgeLogo = badgeLogo;
        }
    }

    /** Chromium switches applied before any user-supplied {@code --cef-arg}. */
    static final String[] DEFAULT_CEF_ARGS = {
            "--disable-background-networking",
            "--disable-component-update",
            "--disable-sync",
            "--disable-breakpad",
            "--disable-extensions",
            "--no-pings",
            "--metrics-recording-only",
            "--disable-features=Translate,OptimizationHints,MediaRouter,InterestFeedContentSuggestions",
    };

    private static void runGui(AppConfig config) {
        AtomicReference<JFrame> frameRef       = new AtomicReference<>();
        AtomicBoolean           shutdownStarted = new AtomicBoolean(false);
        try {
            // ── Parallel startup ────────────────────────────────────────────
            // JCEF initialisation is the slow step (a ~100 MB Chromium
            // download on first run). Everything that does NOT touch AWT/Swing
            // — the sync rooms, the LAN scan inside ServerManager, the FsService
            // config/catalog load and its root health check — runs concurrently
            // on the startup pool instead of serially on the main thread.
            // (JCEF must still be initialised before any Swing/GTK call, so the
            // icon decode below is only SUBMITTED after the JCEF build.)
            Future<SyncRooms> roomsFuture = WorkerPools.startup(SyncRooms::new);
            Future<StartupServices> servicesFuture = WorkerPools.startup(() -> {
                SyncRooms rooms = roomsFuture.get();
                ServerManager servers = new ServerManager(config, rooms);
                servers.log("info", "Desktop app started");
                return new StartupServices(rooms, servers);
            });
            Future<FsService> fsFuture = WorkerPools.startup(() -> {
                FsService fsService = new FsService();
                fsService.setDialogParent(() -> frameRef.get() == null ? null : frameRef.get());
                return fsService;
            });

            // 1. JCEF must be initialised before any Swing/GTK call on Linux
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(new File(System.getProperty("user.home"), ".chasse-studio/jcef-bundle"));
            builder.setProgressHandler(new ConsoleProgressHandler());
            builder.getCefSettings().windowless_rendering_enabled = config.osr();
            // Embedded single-site browser: switch off the background services a
            // general-purpose Chrome runs (component/extension updates, sync,
            // metrics uploads, crash reporter, translate, speculative
            // preconnects). None of them serve the app; each costs threads,
            // memory and start-up network traffic. User --cef-arg values are
            // appended AFTER these, so they win on conflict.
            builder.addJcefArgs(DEFAULT_CEF_ARGS);
            if (!config.cefArgs().isEmpty())
                builder.addJcefArgs(config.cefArgs().toArray(new String[0]));
            if (config.devtoolsPort() > 0)
                builder.getCefSettings().remote_debugging_port = config.devtoolsPort();
            // PERSISTENT browser profile. Without a cache_path CEF runs in-memory
            // (incognito-like): IndexedDB, localStorage and cookies are wiped on
            // every app start, which silently destroyed every deck stored by the
            // browser-store fallback of the web app. This is the root-cause fix
            // for "the desktop app cannot save changes".
            File appDataDir = new File(System.getProperty("user.home"), ".chasse-studio");
            File profileDir = new File(appDataDir, "browser-profile");
            //noinspection ResultOfMethodCallIgnored
            profileDir.mkdirs();
            builder.getCefSettings().root_cache_path = appDataDir.getAbsolutePath();
            builder.getCefSettings().cache_path = profileDir.getAbsolutePath();
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {});
            CefApp cefApp = builder.build();

            // JCEF is up: the window icons + badge logo decode on the startup
            // pool NOW (they used to run on the EDT during the first paint —
            // ImageIO.read + six getScaledInstance calls, ~50 ms of dead UI).
            Future<StartupAssets> assetsFuture = WorkerPools.startup(Main::decodeStartupAssets);

            // 2. LAF (best-effort; we paint everything ourselves anyway)
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}

            // 3. first-run URL prompt (only if user has cleared the URL via user config)
            if (config.appUrl().isBlank()) {
                String url = promptForAppUrl(null);
                if (url == null) { System.out.println("No app URL given — exiting."); System.exit(0); return; }
                config.saveUserAppUrl(url);
            }

            // collect the parallel startup work (all of it is long done by now)
            SyncRooms rooms = roomsFuture.get();
            ServerManager servers = servicesFuture.get().servers();
            final FsService fsService = fsFuture.get();
            StartupAssets assets;
            try {
                assets = assetsFuture.get();
            } catch (Exception e) {
                assets = new StartupAssets(List.of(), null);
            }

            servers.log("info", "Loading " + config.appUrl());
            CefClient client = cefApp.createClient();

            // 4. bridge + same-origin virtual asset interception.
            com.google.gson.JsonObject rootInfo = fsService.getRoot();
            servers.log("info", "Decks folder: "
                    + (rootInfo.get("configured").getAsBoolean()
                        ? rootInfo.get("path").getAsString()
                            + (rootInfo.get("valid").getAsBoolean() ? " (ready)" : " (missing — the app will ask to choose again)")
                        : "not chosen yet"));
            DesktopBridge bridge = new DesktopBridge(client, config, servers, rooms, fsService);
            client.addRequestHandler(new AssetRequestHandler(fsService, config));

            // the native UX the embedded window cannot provide on its own:
            // downloads (Save-as + progress events to the page), JS dialogs
            // (alert/confirm/prompt), beforeunload guards and external links
            DownloadBridge downloadBridge = new DownloadBridge(config,
                    () -> frameRef.get() == null ? null : frameRef.get());
            downloadBridge.setEventSink(bridge::pushEvent);
            bridge.setDownloads(downloadBridge);
            client.addDownloadHandler(downloadBridge);
            client.addJSDialogHandler(WindowDialogs.jsDialogs(
                    () -> frameRef.get() == null ? null : frameRef.get()));
            client.addLifeSpanHandler(WindowDialogs.popupsToSystemBrowser(servers));
            // native file choosers for <input type=file> (Import / Load .smdeck) —
            // the default JCEF dialog handling is unreliable in some builds
            client.addDialogHandler(FileDialogs.fileDialogs(
                    () -> frameRef.get() == null ? null : frameRef.get()));

            final AtomicReference<TitleBar> titleBarRef = new AtomicReference<>();
            client.addDisplayHandler(new CefDisplayHandlerAdapter() {
                @Override public void onAddressChange(CefBrowser b, CefFrame f, String url) {
                    bridge.setCurrentUrl(url);
                }
                @Override public void onTitleChange(CefBrowser b, String title) {
                    // the page's document title is the window title (centre of the bar)
                    TitleBar bar = titleBarRef.get();
                    if (bar != null) SwingUtilities.invokeLater(() -> bar.setTitle(title));
                }
            });

            // after every main-frame load, ask the page for its theme + toolbar
            // colours (and to keep reporting on every toggle) — trusted app only
            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override public void onLoadEnd(CefBrowser b, CefFrame f, int httpStatusCode) {
                    if (f != null && f.isMain() && bridge.isTrusted()) {
                        b.executeJavaScript(THEME_SYNC_JS, f.getURL(), 0);
                    }
                }
            });

            // 5. browser component
            CefBrowser browser = client.createBrowser(config.appUrl(), config.osr(), false);
            bridge.setBrowser(browser);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { servers.stop(); } catch (Exception ignored) {}
            }));

            // 6. Swing window — undecorated so the OS title bar is gone
            final StartupAssets startupAssets = assets;
            SwingUtilities.invokeLater(() -> {
                JFrame frame = new JFrame(APP_NAME + " Studio");
                frame.setUndecorated(true);          // ← remove OS chrome
                frameRef.set(frame);

                // start in the theme the page last reported — no flash of the
                // other palette before the page is up (first run: the web
                // app's own default, dark)
                BarTheme initialTheme = BarTheme.of(config.uiTheme());
                frame.setBackground(initialTheme.panel);

                if (!startupAssets.windowIcons.isEmpty()) frame.setIconImages(startupAssets.windowIcons);

                frame.setSize(1280, 720);
                frame.setMinimumSize(new Dimension(980, 640));
                frame.setLocationRelativeTo(null);

                // an undecorated frame maximizes over the WHOLE screen (taskbar
                // included) unless told its usable area — keep that area right
                // for whichever monitor the window is on (also after a drag)
                frame.setMaximizedBounds(workArea(frame.getGraphicsConfiguration()));
                frame.addComponentListener(new ComponentAdapter() {
                    @Override public void componentMoved(ComponentEvent e) {
                        if (fullscreen || frame.getExtendedState() == Frame.MAXIMIZED_BOTH) return;
                        Rectangle area = workArea(frame.getGraphicsConfiguration());
                        if (!area.equals(frame.getMaximizedBounds())) frame.setMaximizedBounds(area);
                    }
                });

                // resize handles for the undecorated window (the content pane)
                ResizeBorder content = new ResizeBorder(frame);
                content.setBackground(initialTheme.panel);
                frame.setContentPane(content);

                // custom title bar
                final JFrame fr = frame;
                final AtomicReference<TitleBar> self = titleBarRef;
                TitleBar titleBar = new TitleBar(frame, initialTheme, startupAssets.badgeLogo, new TitleBar.Actions(
                        () -> {
                            String url = promptForAppUrl(config.appUrl());
                            if (url != null) { config.saveUserAppUrl(url); browser.loadURL(url); }
                        },
                        browser::reload,
                        () -> shutdown(shutdownStarted, servers, browser, cefApp),
                        () -> toggleFullscreen(fr, self.get()),
                        () -> JOptionPane.showMessageDialog(fr,
                                APP_NAME + " Desktop " + APP_VERSION + "\n\n"
                                        + "Web app: " + (config.appUrl().isBlank() ? "(not configured)" : config.appUrl()) + "\n"
                                        + "Sync server port: " + config.port() + "\n\n"
                                        + "Phone remote starts from the QR panel inside the app.",
                                "About " + APP_NAME, JOptionPane.INFORMATION_MESSAGE),
                        () -> toggleMaximize(fr)));
                titleBarRef.set(titleBar);
                content.add(titleBar, BorderLayout.NORTH);
                content.add(browser.getUIComponent(), BorderLayout.CENTER);

                // maximized ⇄ normal: the resize strip only exists on a normal window
                frame.addWindowStateListener(e -> {
                    if (!fullscreen) content.setEdges((e.getNewState() & Frame.MAXIMIZED_BOTH) == 0);
                    titleBar.repaint(); // the maximize glyph flips to "restore"
                });

                // the page's theme reports → the bar (and remembered for the next launch)
                bridge.setThemeListener(json -> {
                    BarTheme t = BarTheme.fromPage(json);
                    config.saveUiTheme(t.name());
                    SwingUtilities.invokeLater(() -> titleBar.applyTheme(t));
                });

                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                frame.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent e) {
                        shutdown(shutdownStarted, servers, browser, cefApp);
                    }
                });
                frame.setVisible(true);

                // Ctrl+F11 fullscreen (webview swallows plain F11)
                registerF11Toggle(frame, titleBar);

                // keys typed while the WEBVIEW has focus never reach the Swing
                // dispatcher above — so in fullscreen (title bar hidden) Esc /
                // F11 / Ctrl+F11 would be dead and the only way out was the phone.
                // CEF offers every key first: handle the fullscreen ones here.
                client.addKeyboardHandler(new CefKeyboardHandlerAdapter() {
                    @Override public boolean onPreKeyEvent(CefBrowser b, CefKeyboardHandler.CefKeyEvent e,
                                                           BoolRef isKeyboardShortcut) {
                        if (e.type != CefKeyboardHandler.CefKeyEvent.EventType.KEYEVENT_RAWKEYDOWN) return false;
                        switch (fullscreenKeyAction(e.windows_key_code, e.modifiers, fullscreen)) {
                            case EXIT -> SwingUtilities.invokeLater(() -> exitFullscreen(frame, titleBar));
                            case TOGGLE -> SwingUtilities.invokeLater(() -> keyToggleFullscreen(frame, titleBar));
                            default -> { return false; }
                        }
                        return true; // handled here — the page does not also see the key
                    }
                });

                // the webview's Present-mode fullscreen button drives the SAME
                // window toggle through the bridge (toggleFullscreen)
                bridge.setFullscreenToggle(() -> toggleFullscreen(frame, titleBar));

                // phone-remote → window fullscreen
                servers.addRemoteControlListener((deckId, action) ->
                        SwingUtilities.invokeLater(() -> {
                            switch (action) {
                                case "enter"  -> enterFullscreen(frame, titleBar);
                                case "exit"   -> exitFullscreen(frame, titleBar);
                                default       -> toggleFullscreen(frame, titleBar);
                            }
                        }));

                // the parallel startup phase is over — release its threads
                WorkerPools.startupDone();
            });
        } catch (Throwable t) {
            t.printStackTrace();
            String msg = "Failed to start: " + t.getMessage()
                    + "\n\nFirst run downloads ~100 MB of Chromium into ~/.chasse-studio/jcef-bundle.";
            try { JOptionPane.showMessageDialog(null, msg, APP_NAME + " — startup error",
                    JOptionPane.ERROR_MESSAGE); } catch (Exception ignored) {}
            System.exit(1);
        }
    }

    // =========================================================================
    // Fullscreen (Ctrl+F11 or double-click title bar)
    // =========================================================================

    private static void toggleFullscreen(JFrame frame, TitleBar titleBar) {
        if (fullscreen) exitFullscreen(frame, titleBar);
        else            enterFullscreen(frame, titleBar);
    }

    /**
     * The part of a monitor a maximized window may use: the monitor minus the
     * taskbar / docked bars, in the monitor-relative coordinates that
     * {@link JFrame#setMaximizedBounds} expects. Pure — unit-tested.
     */
    static Rectangle usableArea(Rectangle monitor, Insets insets) {
        return new Rectangle(insets.left, insets.top,
                Math.max(1, monitor.width - insets.left - insets.right),
                Math.max(1, monitor.height - insets.top - insets.bottom));
    }

    /** {@link #usableArea} of the monitor {@code gc} describes (null → the primary monitor). */
    static Rectangle workArea(GraphicsConfiguration gc) {
        if (gc == null) {
            gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        }
        return usableArea(gc.getBounds(), Toolkit.getDefaultToolkit().getScreenInsets(gc));
    }

    /** Maximize without covering the taskbar (re-measured every time: the
     *  window may have moved to another monitor or the taskbar may have changed). */
    private static void maximizeKeepingTaskbar(JFrame frame) {
        frame.setMaximizedBounds(workArea(frame.getGraphicsConfiguration()));
        frame.setExtendedState(Frame.MAXIMIZED_BOTH);
    }

    /** The maximize button / title-bar double-click: maximize ⇄ restore (never fullscreen). */
    private static void toggleMaximize(JFrame frame) {
        if (fullscreen) return;
        if (frame.getExtendedState() == Frame.MAXIMIZED_BOTH) frame.setExtendedState(Frame.NORMAL);
        else maximizeKeepingTaskbar(frame);
    }

    private static void setResizeEdges(JFrame frame, boolean on) {
        if (frame.getContentPane() instanceof ResizeBorder rb) rb.setEdges(on);
    }

    /** What a key press means for the window's fullscreen state. */
    enum FsKey { NONE, EXIT, TOGGLE }

    /**
     * Esc and plain F11 leave fullscreen (and only then — outside fullscreen the
     * page keeps them), Ctrl+F11 toggles; anything with Alt is left alone.
     * Pure — unit-tested.
     */
    static FsKey fullscreenKeyAction(int windowsKeyCode, int cefModifiers, boolean inFullscreen) {
        boolean ctrl = (cefModifiers & CEF_CONTROL_DOWN) != 0;
        boolean alt  = (cefModifiers & CEF_ALT_DOWN) != 0;
        if (alt) return FsKey.NONE;
        if (windowsKeyCode == VK_F11 && ctrl) return FsKey.TOGGLE;
        if (inFullscreen && !ctrl && (windowsKeyCode == VK_ESCAPE || windowsKeyCode == VK_F11)) return FsKey.EXIT;
        return FsKey.NONE;
    }

    /** Ctrl+F11 from either key path — ignores the second sight of one key press. */
    private static void keyToggleFullscreen(JFrame frame, TitleBar titleBar) {
        long now = System.nanoTime();
        if (now - lastKeyToggleNanos < 400_000_000L) return;
        lastKeyToggleNanos = now;
        toggleFullscreen(frame, titleBar);
    }

    /**
     * True fullscreen = the undecorated window sized to the whole monitor (title
     * bar hidden). Explicit bounds, not MAXIMIZED_BOTH: a maximized window is
     * confined to the usable area (taskbar visible), which is what the
     * maximize button is for. No dispose/re-show — that re-parented the
     * webview and dropped keyboard focus, one reason the exit keys were dead.
     */
    private static void enterFullscreen(JFrame frame, TitleBar titleBar) {
        if (fullscreen) return;
        GraphicsConfiguration gc = frame.getGraphicsConfiguration();
        Rectangle monitor = (gc != null ? gc
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration())
                .getBounds();
        prevExtendedState = frame.getExtendedState() & Frame.MAXIMIZED_BOTH;
        prevBounds = frame.getBounds();
        fullscreen = true;
        titleBar.setBarVisible(false);
        setResizeEdges(frame, false);
        // leave the maximized state first, or the bounds below are ignored
        frame.setExtendedState(Frame.NORMAL);
        frame.setBounds(monitor);
        frame.toFront();
        showFullscreenHint(frame, titleBar, monitor);
    }

    private static void exitFullscreen(JFrame frame, TitleBar titleBar) {
        if (!fullscreen) return;
        fullscreen = false;
        hideFullscreenHint();
        titleBar.setBarVisible(true);
        if (prevBounds != null) frame.setBounds(prevBounds);
        if (prevExtendedState == Frame.MAXIMIZED_BOTH) {
            maximizeKeepingTaskbar(frame);
        } else {
            frame.setMaximizedBounds(workArea(frame.getGraphicsConfiguration()));
            setResizeEdges(frame, true);
        }
    }

    /**
     * The always-reachable way out of fullscreen: a small "Exit full screen"
     * pill at the top centre. Shown for a few seconds when fullscreen starts and
     * again whenever the pointer touches the top edge (or rests on the pill);
     * it is its own top-level window, so it floats above the webview, and not
     * focusable, so clicking it never steals keyboard focus from the page.
     */
    private static void showFullscreenHint(JFrame frame, TitleBar titleBar, Rectangle monitor) {
        hideFullscreenHint();
        JWindow w = new JWindow(frame);
        w.setFocusableWindowState(false);
        BarTheme th = titleBar.theme();
        JButton b = new JButton("✕   Exit full screen   ·   Esc");
        b.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        b.setForeground(th.ink);
        b.setBackground(th.pop);
        b.setOpaque(true);
        b.setFocusable(false);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(th.line, 1),
                BorderFactory.createEmptyBorder(9, 18, 9, 18)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { b.setBackground(th.panel2); }
            @Override public void mouseExited (MouseEvent e) { b.setBackground(th.pop); }
        });
        b.addActionListener(ev -> exitFullscreen(frame, titleBar));
        w.add(b);
        w.pack();
        w.setLocation(monitor.x + (monitor.width - w.getWidth()) / 2, monitor.y + 10);

        final long[] hideAt = { System.currentTimeMillis() + 3500 };
        w.setVisible(true);
        javax.swing.Timer t = new javax.swing.Timer(120, ev -> {
            PointerInfo pi = MouseInfo.getPointerInfo();
            if (pi == null) return;
            Point p = pi.getLocation();
            boolean nearTop  = monitor.contains(p) && p.y - monitor.y <= 6;
            boolean overPill = w.isVisible() && w.getBounds().contains(p);
            long now = System.currentTimeMillis();
            if (nearTop || overPill) {
                hideAt[0] = now + 1500;
                if (!w.isVisible()) w.setVisible(true);
            } else if (w.isVisible() && now > hideAt[0]) {
                w.setVisible(false);
            }
        });
        fsHint = w;
        fsHintTimer = t;
        t.start();
    }

    private static void hideFullscreenHint() {
        if (fsHintTimer != null) { fsHintTimer.stop(); fsHintTimer = null; }
        if (fsHint != null)      { fsHint.dispose();  fsHint = null; }
    }

    private static void registerF11Toggle(JFrame frame, TitleBar titleBar) {
        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        kfm.addKeyEventDispatcher(new KeyEventDispatcher() {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getID() == KeyEvent.KEY_PRESSED
                        && e.getKeyCode() == KeyEvent.VK_F11
                        && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0
                        && kfm.getActiveWindow() == frame) {
                    SwingUtilities.invokeLater(() -> keyToggleFullscreen(frame, titleBar));
                    e.consume();
                    return true;
                }
                return false;
            }
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Holder for the two startup services constructed off the main thread. */
    private record StartupServices(SyncRooms rooms, ServerManager servers) {}

    /** Decodes the window icons and the title-bar badge off the EDT. */
    private static StartupAssets decodeStartupAssets() {
        List<Image> images = new ArrayList<>();
        Image badge = null;
        try (InputStream in = Main.class.getResourceAsStream("/Logo.png")) {
            if (in == null) {
                System.err.println("Warning: Logo.png not found");
            } else {
                Image base = ImageIO.read(in);
                if (base == null) {
                    System.err.println("Warning: Logo.png unreadable");
                } else {
                    badge = base.getScaledInstance(24, 24, Image.SCALE_SMOOTH);
                    for (int size : new int[]{16, 32, 48, 64, 128, 256})
                        images.add(base.getScaledInstance(size, size, Image.SCALE_SMOOTH));
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: could not load Logo.png: " + e.getMessage());
        }
        return new StartupAssets(images, badge);
    }

    private static List<Image> loadIconImages() {
        return decodeStartupAssets().windowIcons;
    }

    private static String promptForAppUrl(String current) {
        while (true) {
            String url = (String) JOptionPane.showInputDialog(null,
                    "Enter the Chassé Studio web app URL\n(e.g. https://your-site.vercel.app)",
                    APP_NAME + " — first run", JOptionPane.PLAIN_MESSAGE, null, null,
                    current == null || current.isBlank() ? "https://your-site.vercel.app" : current);
            if (url == null) return null;
            url = url.trim();
            if (isValidHttpUrl(url)) return url;
            JOptionPane.showMessageDialog(null,
                    "Please enter a full http(s) URL (including https://).",
                    "Invalid URL", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static boolean isValidHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String s = uri.getScheme();
            return ("http".equals(s) || "https".equals(s)) && uri.getHost() != null;
        } catch (Exception e) { return false; }
    }

    private static void shutdown(AtomicBoolean guard, ServerManager servers,
                                 CefBrowser browser, CefApp cefApp) {
        if (!guard.compareAndSet(false, true)) return;
        Thread exitGuard = new Thread(() -> {
            try { Thread.sleep(3000); Runtime.getRuntime().halt(0); }
            catch (InterruptedException ignored) {}
        }, "exit-guard");
        exitGuard.setDaemon(true);
        exitGuard.start();
        try { servers.stop();      } catch (Exception ignored) {}
        try { browser.close(true); } catch (Exception ignored) {}
        try { cefApp.dispose();    } catch (Exception ignored) {}
        System.exit(0);
    }
}
