package com.chasse.desktop;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
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

/**
 * Chassé Studio Desktop — entry point.
 *
 * <p>The window is undecorated; the OS title bar is replaced by a fully custom
 * Swing title bar that matches the design: {@code </>} badge, app name, muted
 * subtitle, File/View/Help menus, and custom minimize/maximize/close controls
 * on the right — all on a dark {@code #1e1b29} background.</p>
 */
public final class Main {

    public static final String APP_NAME    = "Chassé";
    public static final String APP_SUBTITLE = "slides written as code";
    public static final String APP_VERSION = DesktopBridge.VERSION;

    // ── palette ───────────────────────────────────────────────────────────────
    private static final Color BAR_BG       = new Color(0x1e, 0x1b, 0x29);
    private static final Color BAR_BORDER   = new Color(0x2e, 0x2a, 0x3c);
    private static final Color BAR_FG       = new Color(0xef, 0xea, 0xf7);
    private static final Color BAR_MUTED    = new Color(0x91, 0x89, 0xa6);
    private static final Color BADGE_BG     = new Color(0x5b, 0x52, 0xcc);
    private static final Color WIN_CLOSE_BG = new Color(0xc4, 0x2b, 0x1c); // red on hover
    private static final Color WIN_BTN_HOVER= new Color(0x35, 0x30, 0x47); // subtle hover

    // ── fullscreen state ──────────────────────────────────────────────────────
    private static boolean   fullscreen         = false;
    private static int       prevExtendedState  = Frame.NORMAL;
    private static Rectangle prevBounds         = null;

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
        SyncRooms rooms    = new SyncRooms();
        ServerManager servers = new ServerManager(config, rooms);
        if (config.headless() || config.room() != null) {
            runHeadless(config, rooms, servers);
            return;
        }
        runGui(config, rooms, servers);
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

    private static void runGui(AppConfig config, SyncRooms rooms, ServerManager servers) {
        AtomicReference<JFrame> frameRef       = new AtomicReference<>();
        AtomicBoolean           shutdownStarted = new AtomicBoolean(false);
        try {
            servers.log("info", "Desktop app started");

            // 1. JCEF must be initialised before any Swing/GTK call on Linux
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(new File(System.getProperty("user.home"), ".chasse-studio/jcef-bundle"));
            builder.setProgressHandler(new ConsoleProgressHandler());
            builder.getCefSettings().windowless_rendering_enabled = config.osr();
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

            // 2. LAF (best-effort; we paint everything ourselves anyway)
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}

            // 3. first-run URL prompt (only if user has cleared the URL via user config)
            if (config.appUrl().isBlank()) {
                String url = promptForAppUrl(null);
                if (url == null) { System.out.println("No app URL given — exiting."); System.exit(0); return; }
                config.saveUserAppUrl(url);
            }

            servers.log("info", "Loading " + config.appUrl());
            CefClient client = cefApp.createClient();

            // 4. bridge + same-origin virtual asset interception.
            final FsService fsService = new FsService();
            fsService.setDialogParent(() -> frameRef.get() == null ? null : frameRef.get());
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

            client.addDisplayHandler(new CefDisplayHandlerAdapter() {
                @Override public void onAddressChange(CefBrowser b, CefFrame f, String url) {
                    bridge.setCurrentUrl(url);
                }
                @Override public void onTitleChange(CefBrowser b, String title) {
                    // title bar is fully custom; OS title bar is suppressed
                }
            });

            // 5. browser component
            CefBrowser browser = client.createBrowser(config.appUrl(), config.osr(), false);
            bridge.setBrowser(browser);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { servers.stop(); } catch (Exception ignored) {}
            }));

            // 6. Swing window — undecorated so the OS title bar is gone
            SwingUtilities.invokeLater(() -> {
                JFrame frame = new JFrame(APP_NAME + " \u2014 " + APP_SUBTITLE);
                frame.setUndecorated(true);          // ← remove OS chrome
                frameRef.set(frame);

                List<Image> icons = loadIconImages();
                if (!icons.isEmpty()) frame.setIconImages(icons);

                frame.setSize(1280, 720);
                frame.setMinimumSize(new Dimension(980, 640));
                frame.setLocationRelativeTo(null);

                // custom title bar
                TitleBar titleBar = new TitleBar(frame, config, browser,
                        () -> shutdown(shutdownStarted, servers, browser, cefApp));
                frame.add(titleBar, BorderLayout.NORTH);
                frame.add(browser.getUIComponent(), BorderLayout.CENTER);

                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                frame.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent e) {
                        shutdown(shutdownStarted, servers, browser, cefApp);
                    }
                });
                frame.setVisible(true);

                // Ctrl+F11 fullscreen (webview swallows plain F11)
                registerF11Toggle(frame, titleBar);

                // phone-remote → window fullscreen
                servers.addRemoteControlListener((deckId, action) ->
                        SwingUtilities.invokeLater(() -> {
                            switch (action) {
                                case "enter"  -> enterFullscreen(frame, titleBar);
                                case "exit"   -> exitFullscreen(frame, titleBar);
                                default       -> toggleFullscreen(frame, titleBar);
                            }
                        }));
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
    // Custom title bar
    // =========================================================================

    /**
     * A fully-painted Swing panel that replaces the OS title bar.
     *
     * <p>Layout (left → right):
     * <pre>
     *  [ &lt;/&gt; badge ]  Chassé — slides written as code   [drag space]   File  View  Help  [ − ][ □ ][ × ]
     * </pre>
     * The panel is also the drag handle for the window.</p>
     */
    private static final class TitleBar extends JPanel {

        private static final int HEIGHT = 40;

        // drag state
        private Point dragOrigin;
        private Point frameOrigin;

        TitleBar(JFrame frame, AppConfig config, CefBrowser browser, Runnable shutdown) {
            setLayout(new BorderLayout());
            setOpaque(true);
            setBackground(BAR_BG);
            setPreferredSize(new Dimension(0, HEIGHT));
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BAR_BORDER));

            // ── left: badge + title ───────────────────────────────────────────
            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            left.setOpaque(false);
            left.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            left.add(new BadgeIcon());
            left.add(hgap(8));
            left.add(titleLabel(APP_NAME,     true,  BAR_FG));
            left.add(titleLabel(" \u2014 ",   false, BAR_MUTED));
            left.add(titleLabel(APP_SUBTITLE, false, BAR_MUTED));

            // ── right: menus + window controls ───────────────────────────────
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            right.setOpaque(false);

            // menus — injected into a plain JMenuBar so we get native popup behaviour
            JMenuBar menuBar = buildMenuBar(frame, config, browser, shutdown, this);
            right.add(menuBar);

            right.add(hgap(4));
            right.add(new WinButton(WinButton.Kind.MINIMIZE, frame, null));
            right.add(new WinButton(WinButton.Kind.MAXIMIZE, frame, null));
            right.add(new WinButton(WinButton.Kind.CLOSE,    frame, shutdown));

            add(left,  BorderLayout.WEST);
            add(right, BorderLayout.EAST);

            // drag-to-move (only when window is not maximised)
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (frame.getExtendedState() == Frame.MAXIMIZED_BOTH) return;
                    dragOrigin  = e.getLocationOnScreen();
                    frameOrigin = frame.getLocation();
                }
                @Override public void mouseReleased(MouseEvent e) { dragOrigin = null; }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) toggleFullscreen(frame, TitleBar.this);
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragOrigin == null) return;
                    Point cur = e.getLocationOnScreen();
                    frame.setLocation(
                            frameOrigin.x + cur.x - dragOrigin.x,
                            frameOrigin.y + cur.y - dragOrigin.y);
                }
            });
            setCursor(Cursor.getDefaultCursor());
        }

        /** Exposes visibility for fullscreen toggle (hides the whole bar in true fullscreen). */
        void setBarVisible(boolean v) { setVisible(v); }
    }

    // ── badge: app Logo.png scaled to fit ────────────────────────────────────

    private static final class BadgeIcon extends JPanel {
        private static final int S = 24;
        private final Image logo;

        BadgeIcon() {
            setOpaque(false);
            setPreferredSize(new Dimension(S + 4, S + 4));
            // load Logo.png from classpath; fall back to null (painted as coloured square)
            Image loaded = null;
            try (InputStream in = Main.class.getResourceAsStream("/Logo.png")) {
                if (in != null) {
                    Image raw = ImageIO.read(in);
                    if (raw != null) loaded = raw.getScaledInstance(S, S, Image.SCALE_SMOOTH);
                }
            } catch (IOException ignored) {}
            logo = loaded;
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
            int x = (getWidth()  - S) / 2;
            int y = (getHeight() - S) / 2;
            if (logo != null) {
                // clip to a rounded square so the logo sits in a pill shape
                java.awt.geom.RoundRectangle2D clip =
                        new java.awt.geom.RoundRectangle2D.Float(x, y, S, S, 8, 8);
                g2.setClip(clip);
                g2.drawImage(logo, x, y, S, S, this);
            } else {
                // fallback: purple rounded square with "</>"
                g2.setColor(BADGE_BG);
                g2.fillRoundRect(x, y, S, S, 8, 8);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
                FontMetrics fm = g2.getFontMetrics();
                String t = "</>";
                g2.drawString(t, x + (S - fm.stringWidth(t)) / 2,
                        y + (S - fm.getHeight()) / 2 + fm.getAscent());
            }
            g2.dispose();
        }
    }

    // ── window control buttons (−  □  ×) ─────────────────────────────────────

    private static final class WinButton extends JPanel {
        enum Kind { MINIMIZE, MAXIMIZE, CLOSE }

        private boolean hovered = false;
        private final Kind kind;
        private static final int W = 46, H = 40;

        WinButton(Kind kind, JFrame frame, Runnable shutdown) {
            this.kind = kind;
            setOpaque(false);
            setPreferredSize(new Dimension(W, H));
            setCursor(Cursor.getDefaultCursor());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                @Override public void mouseExited (MouseEvent e) { hovered = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    switch (kind) {
                        case MINIMIZE -> frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED);
                        case MAXIMIZE -> {
                            if (frame.getExtendedState() == Frame.MAXIMIZED_BOTH)
                                frame.setExtendedState(Frame.NORMAL);
                            else
                                frame.setExtendedState(Frame.MAXIMIZED_BOTH);
                        }
                        case CLOSE -> shutdown.run();
                    }
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // hover fill
            if (hovered) {
                g2.setColor(kind == Kind.CLOSE ? WIN_CLOSE_BG : WIN_BTN_HOVER);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }

            // icon
            g2.setColor(BAR_FG);
            int cx = getWidth() / 2, cy = getHeight() / 2;
            switch (kind) {
                case MINIMIZE -> g2.fillRect(cx - 5, cy, 10, 1);
                case MAXIMIZE -> {
                    boolean maximised = false;
                    java.awt.Window w = SwingUtilities.getWindowAncestor(this);
                    if (w instanceof Frame f) maximised = (f.getExtendedState() == Frame.MAXIMIZED_BOTH);
                    if (maximised) {
                        // restore icon: two overlapping squares
                        g2.drawRect(cx - 3, cy - 5, 7, 7);
                        g2.drawRect(cx - 5, cy - 3, 7, 7);
                    } else {
                        g2.drawRect(cx - 5, cy - 5, 10, 10);
                    }
                }
                case CLOSE -> {
                    g2.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
                    g2.drawLine(cx + 5, cy - 5, cx - 5, cy + 5);
                }
            }
            g2.dispose();
        }
    }

    // ── menu bar (popup menus only — the bar itself is styled to be invisible) ─

    /** Applies dark styling directly to a JMenu's existing popup after construction. */
    private static void darkifyPopup(javax.swing.JPopupMenu popup) {
        popup.setOpaque(true);
        popup.setBackground(BAR_BG);
        popup.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        // Swap out the UI so paintBorder and paintComponent are fully ours
        popup.setUI(new javax.swing.plaf.basic.BasicPopupMenuUI() {
            @Override public void paint(Graphics g, javax.swing.JComponent c) {
                // fill background
                g.setColor(BAR_BG);
                g.fillRect(0, 0, c.getWidth(), c.getHeight());
                super.paint(g, c);
            }
        });
        // Override border painting via a custom border that draws our 1px line
        popup.setBorder(new javax.swing.border.AbstractBorder() {
            private static final int PAD = 5;
            @Override public void paintBorder(java.awt.Component c, Graphics g,
                                              int x, int y, int w, int h) {
                g.setColor(BAR_BORDER);
                g.drawRect(x, y, w - 1, h - 1);
            }
            @Override public java.awt.Insets getBorderInsets(java.awt.Component c) {
                return new java.awt.Insets(PAD, 0, PAD, 0);
            }
            @Override public java.awt.Insets getBorderInsets(java.awt.Component c,
                                                              java.awt.Insets insets) {
                insets.set(PAD, 0, PAD, 0); return insets;
            }
        });
    }

    /** Custom painted separator — no LAF involvement. */
    private static JPanel darkSeparator() {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(BAR_BORDER);
                g.fillRect(8, getHeight() / 2, getWidth() - 16, 1);
            }
        };
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(0, 9));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 9));
        return p;
    }

    private static JMenuBar buildMenuBar(JFrame frame, AppConfig config,
                                         CefBrowser browser, Runnable shutdown, TitleBar titleBar) {
        Font menuFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        Font itemFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);

        UIManager.put("PopupMenu.background",             BAR_BG);
        UIManager.put("PopupMenu.border",                 BorderFactory.createEmptyBorder());
        UIManager.put("MenuItem.background",              BAR_BG);
        UIManager.put("MenuItem.foreground",              BAR_FG);
        UIManager.put("MenuItem.selectionBackground",     new Color(0x2e, 0x2a, 0x3c));
        UIManager.put("MenuItem.selectionForeground",     BAR_FG);
        UIManager.put("MenuItem.borderPainted",           Boolean.FALSE);
        UIManager.put("Menu.background",                  BAR_BG);
        UIManager.put("Menu.foreground",                  BAR_FG);
        UIManager.put("Menu.selectionBackground",         new Color(0x2e, 0x2a, 0x3c));
        UIManager.put("Menu.selectionForeground",         BAR_FG);
        UIManager.put("MenuBar.background",               BAR_BG);
        UIManager.put("MenuBar.foreground",               BAR_FG);
        UIManager.put("MenuBar.border",                   BorderFactory.createEmptyBorder());
        UIManager.put("Separator.background",             BAR_BG);
        UIManager.put("Separator.foreground",             BAR_BORDER);

        java.util.function.Consumer<JMenu> styleMenu = m -> {
            m.setFont(menuFont);
            m.setForeground(BAR_FG);
            m.setOpaque(true);
            m.setBackground(BAR_BG);
            m.setBorderPainted(false);
            // darkify the popup AFTER the JMenu has wired it up internally
            darkifyPopup(m.getPopupMenu());
        };
        java.util.function.Consumer<JMenuItem> styleItem = item -> {
            item.setFont(itemFont);
            item.setForeground(BAR_FG);
            item.setBackground(BAR_BG);
            item.setOpaque(true);
            item.setBorderPainted(false);
        };

        // ── File ─────────────────────────────────────────────────────────────
        JMenu file = new JMenu("File");
        styleMenu.accept(file);

        JMenuItem changeUrl = new JMenuItem("Change app URL\u2026");
        changeUrl.addActionListener(ev -> {
            String url = promptForAppUrl(config.appUrl());
            if (url != null) { config.saveUserAppUrl(url); browser.loadURL(url); }
        });
        JMenuItem reload = new JMenuItem("Reload");
        reload.addActionListener(ev -> browser.reload());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(ev -> shutdown.run());

        styleItem.accept(changeUrl); styleItem.accept(reload); styleItem.accept(exit);
        file.add(changeUrl);
        file.add(reload);
        file.getPopupMenu().add(darkSeparator());
        file.add(exit);

        // ── View ─────────────────────────────────────────────────────────────
        JMenu view = new JMenu("View");
        styleMenu.accept(view);

        JMenuItem fsItem = new JMenuItem("Toggle Fullscreen");
        fsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F11, KeyEvent.CTRL_DOWN_MASK));
        fsItem.addActionListener(ev -> toggleFullscreen(frame, titleBar));
        styleItem.accept(fsItem);
        view.add(fsItem);

        // ── Help ─────────────────────────────────────────────────────────────
        JMenu help = new JMenu("Help");
        styleMenu.accept(help);

        JMenuItem about = new JMenuItem("About");
        about.addActionListener(ev -> JOptionPane.showMessageDialog(frame,
                APP_NAME + " Desktop " + APP_VERSION + "\n\n"
                        + "Web app: " + (config.appUrl().isBlank() ? "(not configured)" : config.appUrl()) + "\n"
                        + "Sync server port: " + config.port() + "\n\n"
                        + "Phone remote starts from the QR panel inside the app.",
                "About " + APP_NAME, JOptionPane.INFORMATION_MESSAGE));
        styleItem.accept(about);
        help.add(about);

        // ── The bar itself ────────────────────────────────────────────────────
        JMenuBar bar = new JMenuBar() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(BAR_BG);
                g.fillRect(0, 0, getWidth(), getHeight());
            }
            @Override protected void paintBorder(Graphics g) { /* suppress */ }
        };
        bar.setOpaque(true);
        bar.setBackground(BAR_BG);
        bar.setBorder(BorderFactory.createEmptyBorder());
        bar.add(file); bar.add(view); bar.add(help);
        return bar;
    }

    // =========================================================================
    // Fullscreen (Ctrl+F11 or double-click title bar)
    // =========================================================================

    private static void toggleFullscreen(JFrame frame, TitleBar titleBar) {
        if (fullscreen) exitFullscreen(frame, titleBar);
        else            enterFullscreen(frame, titleBar);
    }

    private static void enterFullscreen(JFrame frame, TitleBar titleBar) {
        if (fullscreen) return;
        prevExtendedState = frame.getExtendedState();
        prevBounds = new Rectangle(frame.getX(), frame.getY(), frame.getWidth(), frame.getHeight());
        titleBar.setBarVisible(false);
        frame.dispose();
        frame.setUndecorated(true);
        frame.setVisible(true);
        frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        fullscreen = true;
    }

    private static void exitFullscreen(JFrame frame, TitleBar titleBar) {
        if (!fullscreen) return;
        frame.dispose();
        frame.setUndecorated(true);   // stay undecorated — we always use the custom bar
        frame.setVisible(true);
        titleBar.setBarVisible(true);
        if (prevBounds != null) frame.setBounds(prevBounds);
        frame.setExtendedState(prevExtendedState);
        fullscreen = false;
    }

    private static void registerF11Toggle(JFrame frame, TitleBar titleBar) {
        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        kfm.addKeyEventDispatcher(new KeyEventDispatcher() {
            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getID() == KeyEvent.KEY_PRESSED
                        && e.getKeyCode() == KeyEvent.VK_F11
                        && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0
                        && kfm.getActiveWindow() == frame) {
                    SwingUtilities.invokeLater(() -> toggleFullscreen(frame, titleBar));
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

    /** Transparent spacer panel for FlowLayout gaps. */
    private static JPanel hgap(int w) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(w, 1));
        return p;
    }

    /** Plain text label for the title row. */
    private static javax.swing.JLabel titleLabel(String text, boolean bold, Color fg) {
        javax.swing.JLabel l = new javax.swing.JLabel(text);
        l.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 13));
        l.setForeground(fg);
        return l;
    }

    private static List<Image> loadIconImages() {
        List<Image> images = new ArrayList<>();
        try (InputStream in = Main.class.getResourceAsStream("/Logo.png")) {
            if (in == null) { System.err.println("Warning: Logo.png not found"); return images; }
            Image base = ImageIO.read(in);
            if (base == null) { System.err.println("Warning: Logo.png unreadable"); return images; }
            for (int size : new int[]{16, 32, 48, 64, 128, 256})
                images.add(base.getScaledInstance(size, size, Image.SCALE_SMOOTH));
        } catch (IOException e) {
            System.err.println("Warning: could not load Logo.png: " + e.getMessage());
        }
        return images;
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
