package com.chasse.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Application configuration.
 *
 * <p>Layering (later wins):</p>
 * <ol>
 *   <li>Bundled {@code /app.properties} from the classpath</li>
 *   <li>User file {@code ~/.chasse-studio/app.properties}</li>
 *   <li>CLI arguments ({@code --app-url=...}, {@code --port=...}, {@code --headless}, {@code --room=...}, {@code --cef-arg=...}, {@code --devtools=...})</li>
 * </ol>
 */
public final class AppConfig {

    static final Path USER_DIR = Paths.get(System.getProperty("user.home"), ".chasse-studio");
    static final Path USER_FILE = USER_DIR.resolve("app.properties");

    static final int DEFAULT_PORT = 8765;
    static final String DEFAULT_PREFERRED_PREFIX = "10.161.79.";

    private String appUrl = "";
    private int port = DEFAULT_PORT;
    private String preferredPrefix = DEFAULT_PREFERRED_PREFIX;
    private boolean headless = false;
    private String room = null;
    private String remotePageTitle = "Chassé Studio — Remote";
    private final List<String> cefArgs = new ArrayList<>();
    private int devtoolsPort = 0;
    private boolean osr = false;

    private AppConfig() {
    }

    /** Loads bundled defaults, overlays the user file, then CLI arguments. */
    public static AppConfig load(String[] args) {
        AppConfig config = new AppConfig();
        Properties props = new Properties();
        // 1. bundled defaults
        try (InputStream in = AppConfig.class.getResourceAsStream("/app.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            System.err.println("Warning: could not read bundled app.properties: " + e.getMessage());
        }
        // 2. user overrides
        if (Files.isRegularFile(USER_FILE)) {
            try (InputStream in = Files.newInputStream(USER_FILE)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Warning: could not read " + USER_FILE + ": " + e.getMessage());
            }
        }
        config.appUrl = props.getProperty("app.url", "").trim();
        config.preferredPrefix = props.getProperty("network.preferred-prefix", DEFAULT_PREFERRED_PREFIX).trim();
        if (config.preferredPrefix.isEmpty()) {
            config.preferredPrefix = DEFAULT_PREFERRED_PREFIX;
        }
        config.remotePageTitle = props.getProperty("remote.page-title", "Chassé Studio — Remote");
        config.port = parsePort(props.getProperty("server.port", String.valueOf(DEFAULT_PORT)));

        // 3. CLI overrides (--key=value or --key value)
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String key = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                key = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            } else if (arg.startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                value = args[i + 1];
            }
            if (value == null) {
                if ("--headless".equals(arg)) {
                    config.headless = true;
                }
                if ("--osr".equals(arg)) {
                    config.osr = true;
                }
                continue;
            }
            switch (key) {
                case "--app-url" -> config.appUrl = value.trim();
                case "--port" -> config.port = parsePort(value);
                case "--room" -> config.room = value;
                case "--cef-arg" -> {
                    if (!value.isBlank()) {
                        config.cefArgs.add(value.trim());
                    }
                }
                case "--devtools" -> config.devtoolsPort = parsePort(value);
                default -> {
                    // unknown flag with a value — ignore
                }
            }
        }

        if (!config.appUrl.isEmpty() && !isHttpUrl(config.appUrl)) {
            throw new IllegalArgumentException("app.url must be a full http(s) URL, got: " + config.appUrl);
        }
        return config;
    }

    private static int parsePort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("server.port must be a number, got: " + raw);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("server.port must be between 1 and 65535, got: " + port);
        }
        return port;
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Hosted web app URL; may be blank (first-run prompt). */
    public String appUrl() {
        return appUrl;
    }

    /** LAN sync server port (1..65535). */
    public int port() {
        return port;
    }

    /** Network prefix that wins the LAN auto-selection (e.g. {@code "10.161.79."}). */
    public String preferredPrefix() {
        return preferredPrefix;
    }

    /** True when running without GUI/JCEF (standalone/diagnostic server). */
    public boolean headless() {
        return headless;
    }

    /** Deck id for headless diagnostic mode ({@code --room=<deckId>}), or null. */
    public String room() {
        return room;
    }

    /** Title used for the bundled remote page. */
    public String remotePageTitle() {
        return remotePageTitle;
    }

    /** Extra Chromium/JCEF switches ({@code --cef-arg=--no-sandbox} etc.). */
    public List<String> cefArgs() {
        return cefArgs;
    }

    /** Remote debugging port for the embedded webview ({@code --devtools=9222}), 0 = off. */
    public int devtoolsPort() {
        return devtoolsPort;
    }

    /** Off-screen rendering webview ({@code --osr}) — for headless Linux / CI environments. */
    public boolean osr() {
        return osr;
    }

    /**
     * The web app's chrome theme as last reported by the page ("dark" /
     * "light"; null = never reported). The native title bar starts in this
     * theme so a restart does not flash the other palette before the page
     * reports again.
     */
    public String uiTheme() {
        return userProperty("ui.theme");
    }

    /** Remembers the page's theme (best-effort, skipped when unchanged). */
    public void saveUiTheme(String theme) {
        if (theme == null || theme.isBlank() || theme.equals(uiTheme())) {
            return;
        }
        saveUserProperty("ui.theme", theme);
    }

    /** Persists {@code app.url} into the user config file (creating directories as needed). */
    public void saveUserAppUrl(String url) {
        this.appUrl = url.trim();
        saveUserProperty("app.url", this.appUrl);
    }

    /**
     * Folder the native Save-as dialog opens at next time (updated after
     * every download; {@code null} = use the system Downloads folder).
     */
    public String downloadsDir() {
        return userProperty("downloads.dir");
    }

    /** Persists the last used downloads folder (best-effort, never throws). */
    public void saveDownloadsDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return; // never clear a remembered folder — keep the last good one
        }
        String current = downloadsDir();
        if (dir.equals(current)) {
            return; // unchanged — avoid pointless disk writes
        }
        saveUserProperty("downloads.dir", dir);
    }

    private static String userProperty(String key) {
        Properties props = new Properties();
        if (Files.isRegularFile(USER_FILE)) {
            try (InputStream in = Files.newInputStream(USER_FILE)) {
                props.load(in);
            } catch (IOException e) {
                return null;
            }
        }
        String v = props.getProperty(key, null);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static void saveUserProperty(String key, String value) {
        Properties props = new Properties();
        if (Files.isRegularFile(USER_FILE)) {
            try (InputStream in = Files.newInputStream(USER_FILE)) {
                props.load(in);
            } catch (IOException e) {
                System.err.println("Warning: could not read " + USER_FILE + ": " + e.getMessage());
            }
        }
        if (value == null) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
        try {
            Files.createDirectories(USER_DIR);
            try (OutputStream out = Files.newOutputStream(USER_FILE)) {
                props.store(out, "Chassé Studio Desktop user configuration (wins over bundled defaults)");
            }
        } catch (IOException e) {
            System.err.println("Warning: could not save " + USER_FILE + ": " + e.getMessage());
        }
    }
}
