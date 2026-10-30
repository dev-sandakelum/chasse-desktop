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
    /** stronger hairline — the popup menu's edge ({@code --ss-line-strong}) */
    final Color lineStrong;
    /** primary text */
    final Color ink;
    /** secondary text — the quiet menu labels */
    final Color ink2;
    /** muted text — the window title */
    final Color mut;
    /**
     * the accent, softened — hover / keyboard focus of a menu item. The web
     * app's {@code --ss-acc-soft} is translucent; this is it composited over
     * {@link #pop} (the bar never paints translucent colours).
     */
    final Color accSoft;

    static final BarTheme DARK = new BarTheme(false,
            new Color(0x1a1723), new Color(0x211e2d), new Color(0x211e2d), new Color(0x2e2a3c), new Color(0x3b3650),
            new Color(0xefeaf7), new Color(0xd9d2e8), new Color(0x9189a6),
            over(new Color(255, 180, 84, 36), new Color(0x211e2d)));   // rgba(255,180,84,.14)

    static final BarTheme LIGHT = new BarTheme(true,
            new Color(0xf7f6fb), new Color(0xffffff), new Color(0xffffff), new Color(0xd8d5e4), new Color(0xc4c0d6),
            new Color(0x1c1930), new Color(0x332d47), new Color(0x625c7a),
            over(new Color(199, 90, 0, 28), new Color(0xffffff)));     // rgba(199,90,0,.11)

    BarTheme(boolean light, Color panel, Color panel2, Color pop, Color line, Color lineStrong,
             Color ink, Color ink2, Color mut, Color accSoft) {
        this.light = light;
        this.panel = panel;
        this.panel2 = panel2;
        this.pop = pop;
        this.line = line;
        this.lineStrong = lineStrong;
        this.ink = ink;
        this.ink2 = ink2;
        this.mut = mut;
        this.accSoft = accSoft;
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
     * line, lineStrong, ink, ink2, mut, accSoft}} where every colour is a CSS
     * colour string as {@code getComputedStyle} returned it (may be
     * empty/missing). {@code accSoft} may carry an alpha — it is composited
     * over the resolved popup colour.
     */
    static BarTheme fromPage(JsonObject o) {
        BarTheme base = of(str(o, "theme"));
        Color pop = pick(o, "pop", base.pop);
        Color accSoft = parseCssRgba(str(o, "accSoft"));
        return new BarTheme(base.light,
                pick(o, "panel", base.panel),
                pick(o, "panel2", base.panel2),
                pop,
                pick(o, "line", base.line),
                pick(o, "lineStrong", base.lineStrong),
                pick(o, "ink", base.ink),
                pick(o, "ink2", base.ink2),
                pick(o, "mut", base.mut),
                accSoft == null ? base.accSoft : over(accSoft, pop));
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
            "^rgba?\\(\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})\\s*(?:[,/]\\s*([0-9.]+%?)\\s*)?\\)$");

    /**
     * {@code #rgb}, {@code #rrggbb}, {@code #rrggbbaa}, {@code rgb(r, g, b)},
     * {@code rgba(r, g, b, a)} and the space-separated forms → an OPAQUE colour
     * (the bar is never translucent); anything else → null.
     */
    static Color parseCss(String css) {
        Color c = parseCssRgba(css);
        return c == null ? null : new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Like {@link #parseCss} but keeps the alpha ({@code #rrggbbaa}, {@code rgba(…, a)}, {@code rgb(… / a%)}). */
    static Color parseCssRgba(String css) {
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
            int a = x.length() == 8 ? Integer.parseInt(x.substring(6, 8), 16) : 255;
            return new Color((rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff, a);
        }
        Matcher m = RGB.matcher(s);
        if (m.matches()) {
            int r = Integer.parseInt(m.group(1));
            int g = Integer.parseInt(m.group(2));
            int b = Integer.parseInt(m.group(3));
            if (r > 255 || g > 255 || b > 255) {
                return null;
            }
            int a = 255;
            String as = m.group(4);
            if (as != null) {
                try {
                    double f = as.endsWith("%") ? Double.parseDouble(as.substring(0, as.length() - 1)) / 100.0
                                                : Double.parseDouble(as);
                    a = (int) Math.round(Math.max(0.0, Math.min(1.0, f)) * 255.0);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return new Color(r, g, b, a);
        }
        return null;
    }

    /** {@code src} (with alpha) composited over the opaque {@code dst} → an opaque colour. */
    static Color over(Color src, Color dst) {
        double a = src.getAlpha() / 255.0;
        return new Color(
                (int) Math.round(dst.getRed() + (src.getRed() - dst.getRed()) * a),
                (int) Math.round(dst.getGreen() + (src.getGreen() - dst.getGreen()) * a),
                (int) Math.round(dst.getBlue() + (src.getBlue() - dst.getBlue()) * a));
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
