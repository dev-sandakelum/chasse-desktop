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
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
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
 * <p>GUI mode loads the hosted Chassé Studio web app inside an embedded
 * Chromium webview (JCEF) and exposes the embedded LAN sync server to the page
 * through the JCEF JavaScript bridge. Headless mode ({@code --headless} or
 * {@code --room=<deckId>}) runs only the sync server — documented standalone /
 * diagnostic mode.</p>
 */
public final class Main {

    public static final String APP_NAME = "Chassé Studio";
    public static final String APP_VERSION = DesktopBridge.VERSION;

    /** F11 fullscreen toggle state for the main window. */
    private static boolean fullscreen = false;
    private static int prevExtendedState = JFrame.NORMAL;
    private static Rectangle prevBounds = null;

    private Main() {
    }

    public static void main(String[] args) {
        AppConfig config;
        try {
            config = AppConfig.load(args);
        } catch (Exception e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        SyncRooms rooms = new SyncRooms();
        ServerManager servers = new ServerManager(config, rooms);

        if (config.headless() || config.room() != null) {
            runHeadless(config, rooms, servers);
            return;
        }
        runGui(config, rooms, servers);
    }

    /* ------------------------------------------------------------------ */
    /* Headless / diagnostic mode                                          */
    /* ------------------------------------------------------------------ */

    private static void runHeadless(AppConfig config, SyncRooms rooms, ServerManager servers) {
        System.out.println("Chassé Studio Desktop — headless mode (no GUI, no webview)");
        // diagnostics: mirror the server log + status changes to stdout
        servers.addListener(new ServerManager.Listener() {
            @Override
            public void onStatus(String status, int port, List<LanAddresses.LanAddress> addresses,
                                 String selectedAddress, String error) {
                System.out.println("[server] status: " + status
                        + (error == null ? "" : " — " + error));
            }

            @Override
            public void onLog(long ts, String level, String text) {
                System.out.println("[server:" + level + "] " + text);
            }
        });
        servers.log("info", "Desktop app started (headless mode)");

        if (config.room() != null) {
            // diagnostic demo room: seed a few slides so a phone can flip through
            List<SyncRooms.SlideRef> demoSlides = List.of(
                    new SyncRooms.SlideRef("demo-1", "Demo slide 1 — Welcome"),
                    new SyncRooms.SlideRef("demo-2", "Demo slide 2 — Phone remote works"),
                    new SyncRooms.SlideRef("demo-3", "Demo slide 3 — Prev / Next / notes"),
                    new SyncRooms.SlideRef("demo-4", "Demo slide 4 — Swipe too"),
                    new SyncRooms.SlideRef("demo-5", "Demo slide 5 — The end"));
            SyncRooms.JoinResult join = rooms.join(config.room(), demoSlides);
            rooms.setNotes(config.room(), "demo-3", "Notes are just text.\nThey support line breaks.");
            servers.start();
            String link = "http://" + servers.bestAddress() + ":" + config.port()
                    + "/remote?deck=" + config.room() + "&t=" + join.token;
            System.out.println();
            System.out.println("  Phone remote link (open on a phone on the same network):");
            System.out.println("    " + link);
            System.out.println();
            servers.log("info", "Room '" + config.room() + "' ready — join link: " + link);
        } else {
            servers.start();
            System.out.println("Sync server only (no room). Create a diagnostic room with --room=<deckId>.");
        }

        System.out.println("Press Ctrl+C to stop.");
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                servers.stop();
                System.out.println("Sync server stopped.");
            } catch (Exception e) {
                // shutting down anyway
            }
            done.countDown();
        }));
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* ------------------------------------------------------------------ */
    /* GUI mode                                                            */
    /* ------------------------------------------------------------------ */

    private static void runGui(AppConfig config, SyncRooms rooms, ServerManager servers) {
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicBoolean shutdownStarted = new AtomicBoolean(false);

        try {
            servers.log("info", "Desktop app started");

            // 1. build JCEF FIRST — on Linux, CefApp.startup() (inside build) must be
            // the very first Xlib call in the process (XInitThreads), so no Swing/GTK
            // dialog may run before this point. (Downloads natives on first run.)
            CefAppBuilder builder = new CefAppBuilder();
            builder.setInstallDir(new File(System.getProperty("user.home"), ".chasse-studio/jcef-bundle"));
            builder.setProgressHandler(new ConsoleProgressHandler());
            builder.getCefSettings().windowless_rendering_enabled = config.osr();
            // optional extra Chromium switches (e.g. --cef-arg=--no-sandbox on locked-down Linux)
            if (!config.cefArgs().isEmpty()) {
                builder.addJcefArgs(config.cefArgs().toArray(new String[0]));
            }
            // optional DevTools remote debugging (--devtools=9222 → http://127.0.0.1:9222)
            if (config.devtoolsPort() > 0) {
                builder.getCefSettings().remote_debugging_port = config.devtoolsPort();
            }
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            });
            CefApp cefApp = builder.build();

            // 2. now that X11/CEF is up, Swing is safe to use
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // keep the default look & feel
            }

            // 3. first run: ask for the web app URL (validated, saved to the user config)
            if (config.appUrl().isBlank()) {
                String url = promptForAppUrl(null);
                if (url == null) {
                    System.out.println("No app URL given — exiting.");
                    System.exit(0);
                    return;
                }
                config.saveUserAppUrl(url);
            }

            servers.log("info", "Loading " + config.appUrl());
            CefClient client = cefApp.createClient();

            // 4. bridge + display handler (origin tracking for trust + window title)
            DesktopBridge bridge = new DesktopBridge(client, config, servers, rooms);
            client.addDisplayHandler(new CefDisplayHandlerAdapter() {
                @Override
                public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
                    bridge.setCurrentUrl(url);
                }

                @Override
                public void onTitleChange(CefBrowser browser, String title) {
                    SwingUtilities.invokeLater(() -> {
                        JFrame frame = frameRef.get();
                        if (frame != null && title != null) {
                            frame.setTitle(title);
                        }
                    });
                }
            });

            // 5. browser (windowed rendering by default; --osr for headless environments)
            CefBrowser browser = client.createBrowser(config.appUrl(), config.osr(), false);
            bridge.setBrowser(browser);

            // whatever kills the JVM, the sync server should stop cleanly
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    servers.stop();
                } catch (Exception e) {
                    // shutting down anyway
                }
            }));

            // 5. Swing window
            SwingUtilities.invokeLater(() -> {
                JFrame frame = new JFrame(APP_NAME);
                frameRef.set(frame);
                List<Image> icons = loadIconImages();
                if (!icons.isEmpty()) {
                    frame.setIconImages(icons);
                }
                frame.setSize(1280, 860);
                frame.setMinimumSize(new Dimension(980, 640));
                frame.setLocationRelativeTo(null);
                frame.add(browser.getUIComponent(), BorderLayout.CENTER);
                JMenuBar menuBar = buildMenu(frame, config, browser,
                        () -> shutdown(shutdownStarted, servers, browser, cefApp));
                frame.setJMenuBar(menuBar);
                frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        shutdown(shutdownStarted, servers, browser, cefApp);
                    }
                });
                frame.setVisible(true);

                // F11 fullscreen toggle: catches F11 anywhere in this window,
                // including when the embedded webview holds keyboard focus (OSR).
                registerF11Toggle(frame, menuBar);

                // phone remote → desktop window fullscreen control
                servers.addRemoteControlListener((deckId, action) ->
                        SwingUtilities.invokeLater(() -> {
                            switch (action) {
                                case "enter" -> enterFullscreen(frame, menuBar);
                                case "exit" -> exitFullscreen(frame, menuBar);
                                default -> toggleFullscreen(frame, menuBar);
                            }
                        }));
            });
        } catch (Throwable t) {
            t.printStackTrace();
            String message = "Failed to start the desktop app: " + t.getMessage()
                    + "\n\nThe first run downloads the Chromium webview (~100 MB) into"
                    + " ~/.chasse-studio/jcef-bundle — check your internet connection and retry.";
            try {
                JOptionPane.showMessageDialog(null, message, APP_NAME + " — startup error",
                        JOptionPane.ERROR_MESSAGE);
            } catch (Exception ignored) {
                // no display available
            }
            System.exit(1);
        }
    }

    private static JMenuBar buildMenu(JFrame frame, AppConfig config, CefBrowser browser, Runnable shutdown) {
        JMenuBar menuBar = new JMenuBar();

        JMenu file = new JMenu("File");
        JMenuItem changeUrl = new JMenuItem("Change app URL…");
        changeUrl.addActionListener(ev -> {
            String url = promptForAppUrl(config.appUrl());
            if (url != null) {
                config.saveUserAppUrl(url);
                browser.loadURL(url);
            }
        });
        JMenuItem reload = new JMenuItem("Reload");
        reload.addActionListener(ev -> browser.reload());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(ev -> shutdown.run());
        file.add(changeUrl);
        file.add(reload);
        file.addSeparator();
        file.add(exit);
        menuBar.add(file);

        JMenu view = new JMenu("View");
        JMenuItem fullscreenItem = new JMenuItem("Toggle Fullscreen");
        fullscreenItem.setToolTipText("Press Ctrl+F11 to enter or leave fullscreen");
        fullscreenItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F11, KeyEvent.CTRL_DOWN_MASK));
        fullscreenItem.addActionListener(ev -> toggleFullscreen(frame, menuBar));
        view.add(fullscreenItem);
        menuBar.add(view);

        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About");
        about.addActionListener(ev -> JOptionPane.showMessageDialog(frame,
                APP_NAME + " Desktop " + APP_VERSION + "\n\n"
                        + "Web app: " + (config.appUrl().isBlank() ? "(not configured)" : config.appUrl()) + "\n"
                        + "Sync server port: " + config.port() + "\n\n"
                        + "The phone remote server starts from the QR panel inside the app.",
                "About " + APP_NAME, JOptionPane.INFORMATION_MESSAGE));
        help.add(about);
        menuBar.add(help);
        return menuBar;
    }

    /* ------------------------------------------------------------------ */
    /* Fullscreen toggle (Ctrl+F11)                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Toggles the main window between true fullscreen (no decorations, no menu
     * bar, fills the screen) and its previous normal-window state/size/position.
     *
     * <p>Changing {@code undecorated} requires disposing and re-showing the
     * frame; the embedded JCEF webview survives this re-parenting.</p>
     */
    private static void toggleFullscreen(JFrame frame, JMenuBar menuBar) {
        if (fullscreen) {
            exitFullscreen(frame, menuBar);
        } else {
            enterFullscreen(frame, menuBar);
        }
    }

    /** Enters true fullscreen (no decorations / menu bar, fills the screen). */
    private static void enterFullscreen(JFrame frame, JMenuBar menuBar) {
        if (fullscreen) {
            return;
        }
        prevExtendedState = frame.getExtendedState();
        prevBounds = new Rectangle(frame.getX(), frame.getY(),
                frame.getWidth(), frame.getHeight());
        menuBar.setVisible(false);
        frame.dispose();
        frame.setUndecorated(true);
        frame.setVisible(true);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        fullscreen = true;
    }

    /** Leaves fullscreen and restores the previous window geometry. */
    private static void exitFullscreen(JFrame frame, JMenuBar menuBar) {
        if (!fullscreen) {
            return;
        }
        frame.dispose();
        frame.setUndecorated(false);
        frame.setVisible(true);
        menuBar.setVisible(true);
        if (prevBounds != null) {
            frame.setBounds(prevBounds);
        }
        frame.setExtendedState(prevExtendedState);
        fullscreen = false;
    }

    /**
     * Registers a {@link KeyEventDispatcher} so Ctrl+F11 toggles fullscreen even
     * when the embedded webview (a heavyweight AWT component) holds keyboard
     * focus. The dispatcher consumes the key so the View menu accelerator
     * doesn't double-fire; the menu accelerator remains as the Swing-side
     * fallback. (Plain F11 is swallowed by the native Chromium window.)
     */
    private static void registerF11Toggle(JFrame frame, JMenuBar menuBar) {
        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        kfm.addKeyEventDispatcher(new KeyEventDispatcher() {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getID() == KeyEvent.KEY_PRESSED
                        && e.getKeyCode() == KeyEvent.VK_F11
                        && (e.getModifiersEx() & KeyEvent.CTRL_DOWN_MASK) != 0
                        && kfm.getActiveWindow() == frame) {
                    SwingUtilities.invokeLater(() -> toggleFullscreen(frame, menuBar));
                    e.consume();
                    return true; // consumed — prevent the menu accelerator double-fire
                }
                return false;
            }
        });
    }

    /**
     * Loads the bundled {@code /Logo.png} from the classpath and returns it
     * scaled to the common window-icon sizes, so the app logo looks crisp in
     * the title bar, taskbar / dock, and alt-tab switcher across platforms.
     * Returns an empty list (and logs a warning) if the logo is missing.
     */
    private static List<Image> loadIconImages() {
        List<Image> images = new ArrayList<>();
        try (InputStream in = Main.class.getResourceAsStream("/Logo.png")) {
            if (in == null) {
                System.err.println("Warning: Logo.png not found on the classpath");
                return images;
            }
            Image base = ImageIO.read(in);
            if (base == null) {
                System.err.println("Warning: Logo.png could not be decoded");
                return images;
            }
            for (int size : new int[]{16, 32, 48, 64, 128, 256}) {
                images.add(base.getScaledInstance(size, size, Image.SCALE_SMOOTH));
            }
        } catch (IOException e) {
            System.err.println("Warning: could not load Logo.png: " + e.getMessage());
        }
        return images;
    }

    /** Prompts (and re-prompts on invalid input) for a full http(s) URL. Null = cancelled. */
    private static String promptForAppUrl(String current) {
        while (true) {
            String url = (String) JOptionPane.showInputDialog(
                    null,
                    "Enter the Chassé Studio web app URL\n(e.g. https://your-site.vercel.app)",
                    APP_NAME + " — first run",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    null,
                    current == null || current.isBlank() ? "https://your-site.vercel.app" : current);
            if (url == null) {
                return null; // cancelled
            }
            url = url.trim();
            if (isValidHttpUrl(url)) {
                return url;
            }
            JOptionPane.showMessageDialog(null,
                    "Please enter a full http(s) URL (including https://).",
                    "Invalid URL", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static boolean isValidHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Idempotent shutdown: stop server → close browser → dispose CEF → exit (3s hard guard). */
    private static void shutdown(AtomicBoolean guard, ServerManager servers, CefBrowser browser, CefApp cefApp) {
        if (!guard.compareAndSet(false, true)) {
            return;
        }
        // hard-exit guard in case CEF disposal hangs
        Thread exitGuard = new Thread(() -> {
            try {
                Thread.sleep(3000);
                Runtime.getRuntime().halt(0);
            } catch (InterruptedException e) {
                // normal exit beat us to it
            }
        }, "exit-guard");
        exitGuard.setDaemon(true);
        exitGuard.start();

        try {
            servers.stop();
        } catch (Exception e) {
            // best effort
        }
        try {
            browser.close(true);
        } catch (Exception e) {
            // ignore
        }
        try {
            cefApp.dispose();
        } catch (Exception e) {
            // ignore
        }
        System.exit(0);
    }
}
