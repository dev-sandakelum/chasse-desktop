package com.chasse.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serves the bundled, fully self-contained phone remote page
 * (classpath resource {@code /remote/index.html}) at {@code GET /remote}.
 * The page is static: it reads {@code deck} and {@code t} from its own
 * query string, so nothing is injected.
 */
public final class RemotePage {

    private static final String DEFAULT_TITLE = "Chass\u00e9 Studio \u2014 Remote";

    private static volatile String cached;

    private RemotePage() {
    }

    /**
     * Returns the remote page HTML with the configured {@code remote.page-title}
     * applied (loaded once, UTF-8; the title substitution happens on a copy so
     * the cached original stays reusable).
     */
    public static String html(String title) {
        String html = html();
        if (title == null || title.isBlank() || title.equals(DEFAULT_TITLE)) {
            return html;
        }
        return html.replace("<title>" + DEFAULT_TITLE + "</title>",
                "<title>" + escape(title) + "</title>");
    }

    /** Returns the remote page HTML as bundled (loaded once, UTF-8). */
    public static String html() {
        String html = cached;
        if (html == null) {
            synchronized (RemotePage.class) {
                html = cached;
                if (html == null) {
                    html = load();
                    cached = html;
                }
            }
        }
        return html;
    }

    private static String load() {
        try (InputStream in = RemotePage.class.getResourceAsStream("/remote/index.html")) {
            if (in == null) {
                return "<!doctype html><html><head><meta charset=\"utf-8\">"
                        + "<title>Chassé Studio — Remote</title></head>"
                        + "<body style=\"background:#12101a;color:#efeaf7;font-family:system-ui;"
                        + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">"
                        + "<p>Remote page missing from the application bundle.</p></body></html>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<!doctype html><html><head><meta charset=\"utf-8\">"
                    + "<title>Chassé Studio — Remote</title></head>"
                    + "<body style=\"background:#12101a;color:#efeaf7;font-family:system-ui;"
                    + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0\">"
                    + "<p>Could not load the remote page: " + escape(e.getMessage()) + "</p></body></html>";
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
