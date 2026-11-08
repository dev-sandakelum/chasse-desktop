package com.chasse.desktop;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OS-drawn outline (Windows 11 DWM) and the themed dialogs: the pure
 * parts. The DWM call itself needs a window and Windows 11; what can be
 * checked everywhere is the colour encoding it is handed, that a missing
 * DWM never throws, and the dialog titles the page's JS dialogs get.
 */
class WindowChromeTest {

    @Test
    void colorrefIsBlueGreenRed() {
        // GDI COLORREF: 0x00BBGGRR — the opposite byte order of a CSS hex colour
        assertEquals(0x00503b3b, WindowChrome.colorref(new Color(0x3b3b50)));
        assertEquals(0x000000ff, WindowChrome.colorref(Color.RED));
        assertEquals(0x00ff0000, WindowChrome.colorref(Color.BLUE));
        assertEquals(0x00ffffff, WindowChrome.colorref(Color.WHITE));
        // the theme's strong line colours as the border
        assertEquals(0x0050363b, WindowChrome.colorref(BarTheme.DARK.lineStrong));
        assertEquals(0x00d6c0c4, WindowChrome.colorref(BarTheme.LIGHT.lineStrong));
    }

    @Test
    void noWindowIsNeverAnError() {
        // nothing to apply to → false, no exception (the caller falls back to the painted hairline)
        assertFalse(WindowChrome.apply(null, true, Color.RED));
        // probing is idempotent and never throws, DWM or not
        boolean a = WindowChrome.available();
        assertEquals(a, WindowChrome.available());
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            assertFalse(a, "DWM is Windows-only");
        }
    }

    @Test
    void jsDialogTitlesAreTheHost() {
        assertEquals("studio-chasse.vercel.app says", WindowDialogs.appTitle("https://studio-chasse.vercel.app/deck/1"));
        assertEquals("localhost says", WindowDialogs.appTitle("http://localhost:3000/"));
        assertEquals(Main.APP_NAME, WindowDialogs.appTitle(null));
        assertEquals(Main.APP_NAME, WindowDialogs.appTitle(""));
        assertEquals(Main.APP_NAME, WindowDialogs.appTitle("not a url at all ::"));
    }

    @Test
    void dialogPaletteMeetsContrast() {
        for (BarTheme t : new BarTheme[]{BarTheme.DARK, BarTheme.LIGHT}) {
            assertTrue(BarTheme.contrast(t.ink, t.pop) >= 4.5, "dialog title on the card");
            assertTrue(BarTheme.contrast(t.ink2, t.pop) >= 4.5, "dialog message on the card");
            assertTrue(BarTheme.contrast(t.ink, t.bg) >= 4.5, "typed text in the input field");
            // the accent button: the web's own pairing (white on the light accent is large-text AA)
            assertTrue(BarTheme.contrast(t.accInk, t.acc) >= 3.0, "accent button label");
            assertTrue(BarTheme.contrast(t.acc, t.pop) >= 3.0, "accent button against the card");
            assertTrue(BarTheme.contrast(t.line, t.pop) < BarTheme.contrast(t.hovline, t.pop), "hover edge is stronger");
        }
    }
}
