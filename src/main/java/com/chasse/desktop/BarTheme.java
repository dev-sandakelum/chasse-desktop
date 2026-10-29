package com.chasse.desktop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Color;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The colours of the native title bar — the web app's own chrome palette
 * ({@code --ss-panel}, {@code --ss-line}, {@code --ss-ink}…) so the bar and
 * the web toolbar directly under it read as ONE surface.
 *
 * <p>{@link #DARK} / {@link #LIGHT} are the palette as shipped in the web
 * app's stylesheet (the bar starts from them, so there is no flash before the
 * page reports). Once the page is up it reports its LIVE computed values
 * through the {@code ui.theme} bridge command ({@link #fromPage}) — those win,
 * so a restyled web app never drifts from the bar. A value the page could not
 * resolve falls back to the built-in colour of the same theme.</p>
 */
final class BarTheme {

    final boolean light;
    /** the toolbar surface (what the bar is filled with) */
    final Color panel;
    /** raised surface — hover fills */
    final Color panel2;
    /** popup menu surface */
    final Color pop;
    /** hairlines */
    final Color line;
    /** primary text */
    final Color ink;
    /** secondary text — the quiet menu labels */
    final Color ink2;
    /** muted text — the window title */
    final Color mut;

    static final BarTheme DARK = new BarTheme(false,
            new Color(0x1a1723), new Color(0x211e2d), new Color(0x211e2d), new Color(0x2e2a3c),
            new Color(0xefeaf7), new Color(0xd9d2e8), new Color(0x9189a6));

    static final BarTheme LIGHT = new BarTheme(true,
            new Color(0xf7f6fb), new Color(0xffffff), new Color(0xffffff), new Color(0xd8d5e4),
            new Color(0x1c1930), new Color(0x332d47), new Color(0x625c7a));

    BarTheme(boolean light, Color panel, Color panel2, Color pop, Color line, Color ink, Color ink2, Color mut) {
        this.light = light;
        this.panel = panel;
        this.panel2 = panel2;
        this.pop = pop;
        this.line = line;
        this.ink = ink;
        this.ink2 = ink2;
        this.mut = mut;
    }

    /** "light" → {@link #LIGHT}, anything else → {@link #DARK} (the web app's own default). */
    static BarTheme of(String name) {
        return "light".equalsIgnoreCase(name == null ? "" : name.trim()) ? LIGHT : DARK;
    }

    String name() {
        return light ? "light" : "dark";
    }

    /**
     * The page's report: {@code {theme:"dark"|"light", panel, panel2, pop,
     * line, ink, ink2, mut}} where every colour is a CSS colour string as
     * {@code getComputedStyle} returned it (may be empty/missing).
     */
    static BarTheme fromPage(JsonObject o) {
        BarTheme base = of(str(o, "theme"));
        return new BarTheme(base.light,
                pick(o, "panel", base.panel),
                pick(o, "panel2", base.panel2),
                pick(o, "pop", base.pop),
                pick(o, "line", base.line),
                pick(o, "ink", base.ink),
                pick(o, "ink2", base.ink2),
                pick(o, "mut", base.mut));
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static Color pick(JsonObject o, String key, Color fallback) {
        Color c = parseCss(str(o, key));
        return c == null ? fallback : c;
    }

    private static final Pattern HEX = Pattern.compile("^#([0-9a-f]{3}|[0-9a-f]{6}|[0-9a-f]{8})$");
    private static final Pattern RGB = Pattern.compile(
            "^rgba?\\(\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})\\s*(?:[,/]\\s*[0-9.]+%?\\s*)?\\)$");

    /**
     * {@code #rgb}, {@code #rrggbb}, {@code #rrggbbaa}, {@code rgb(r, g, b)},
     * {@code rgba(r, g, b, a)} and the space-separated forms → an OPAQUE colour
     * (the bar is never translucent); anything else → null.
     */
    static Color parseCss(String css) {
        if (css == null) {
            return null;
        }
        String s = css.trim().toLowerCase(Locale.ROOT);
        Matcher h = HEX.matcher(s);
        if (h.matches()) {
            String x = h.group(1);
            if (x.length() == 3) {
                x = "" + x.charAt(0) + x.charAt(0) + x.charAt(1) + x.charAt(1) + x.charAt(2) + x.charAt(2);
            }
            int rgb = Integer.parseInt(x.substring(0, 6), 16);
            return new Color(rgb);
        }
        Matcher m = RGB.matcher(s);
        if (m.matches()) {
            int r = Integer.parseInt(m.group(1));
            int g = Integer.parseInt(m.group(2));
            int b = Integer.parseInt(m.group(3));
            if (r > 255 || g > 255 || b > 255) {
                return null;
            }
            return new Color(r, g, b);
        }
        return null;
    }

    /* ---------------- WCAG ---------------- */

    private static double channel(int v) {
        double c = v / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static double luminance(Color c) {
        return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
    }

    /** WCAG 2 contrast ratio (1 … 21). AA for normal text is ≥ 4.5. */
    static double contrast(Color a, Color b) {
        double la = luminance(a);
        double lb = luminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }
}
