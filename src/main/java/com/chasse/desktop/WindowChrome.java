package com.chasse.desktop;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.awt.Color;
import java.awt.Window;
import java.util.Locale;

/**
 * The window's outline, done by the OS where the OS can: on Windows 11 the
 * Desktop Window Manager rounds the corners of the undecorated window (and
 * of the themed dialogs), draws the 1 px border in the theme's own line
 * colour and the system shadow — anti-aliased, the same outline every
 * modern Windows app has. Nothing is clipped by hand, so the embedded
 * Chromium view stays untouched.
 *
 * <p>Everything here is best-effort and guarded: no DWM (Windows 10 or
 * earlier, another OS, JNA unavailable) means the call silently does
 * nothing and the window keeps square corners with a painted hairline
 * outline instead (see {@link ResizeBorder}). The dialogs and popups are
 * per-pixel translucent windows and round themselves.</p>
 */
final class WindowChrome {

    /** the radius Windows 11 rounds a normal window by (what the dialog veil mirrors) */
    static final int FRAME_RADIUS = 8;

    // dwmapi.h — DWMWINDOWATTRIBUTE
    private static final int DWMWA_BORDER_COLOR = 34;
    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    // DWM_WINDOW_CORNER_PREFERENCE
    private static final int DWMWCP_DONOTROUND = 1;
    private static final int DWMWCP_ROUND = 2;
    /** COLORREF sentinel: no border at all */
    private static final int DWMWA_COLOR_NONE = 0xFFFFFFFE;

    private interface Dwm extends Library {
        int DwmSetWindowAttribute(Pointer hwnd, int attribute, Pointer value, int size);
    }

    private static volatile Dwm dwm;
    private static volatile boolean probed;

    private WindowChrome() {
    }

    /** True once the DWM library is reachable (Windows only). Probed lazily, once. */
    static boolean available() {
        if (!probed) {
            synchronized (WindowChrome.class) {
                if (!probed) {
                    Dwm d = null;
                    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                            && !Boolean.getBoolean("chasse.noDwm")) {
                        try {
                            d = Native.load("dwmapi", Dwm.class);
                        } catch (Throwable t) {
                            d = null; // no JNA natives / no dwmapi — square corners then
                        }
                    }
                    dwm = d;
                    probed = true;
                }
            }
        }
        return dwm != null;
    }

    /**
     * Round the window's corners (Windows 11) and draw the DWM border in
     * {@code border} — or no border at all when {@code border} is null. The
     * window must be displayable (its native peer exists: after
     * {@code pack()} / {@code setVisible(true)}). Returns true if the OS took
     * the request, false if the caller has to fall back.
     */
    static boolean apply(Window w, boolean rounded, Color border) {
        if (w == null || !available()) {
            return false;
        }
        try {
            Pointer hwnd = Native.getComponentPointer(w);
            if (hwnd == null) {
                return false;
            }
            boolean ok = set(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, rounded ? DWMWCP_ROUND : DWMWCP_DONOTROUND);
            set(hwnd, DWMWA_BORDER_COLOR, border == null ? DWMWA_COLOR_NONE : colorref(border));
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean set(Pointer hwnd, int attribute, int value) {
        Memory m = new Memory(4);
        try {
            m.setInt(0, value);
            return dwm.DwmSetWindowAttribute(hwnd, attribute, m, 4) == 0; // S_OK
        } finally {
            m.close();
        }
    }

    /** A GDI {@code COLORREF}: {@code 0x00BBGGRR}. Pure — unit-tested. */
    static int colorref(Color c) {
        return (c.getBlue() << 16) | (c.getGreen() << 8) | c.getRed();
    }
}
