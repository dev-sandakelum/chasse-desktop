package com.chasse.desktop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The native title bar's palette and geometry rules: the built-in palettes
 * (the web app's stylesheet values) meet WCAG AA, the page's live colour
 * report overrides them with safe fallbacks, and the resize arithmetic of
 * the undecorated window is right. The Swing bar itself needs a display.
 */
class BarThemeTest {

    private static final double AA = 4.5;

    @Test
    void builtInDarkPaletteMeetsAA() {
        BarTheme t = BarTheme.DARK;
        assertTrue(BarTheme.contrast(t.ink, t.panel) >= AA, "ink on panel");
        assertTrue(BarTheme.contrast(t.ink2, t.panel) >= AA, "menu labels on panel");
        assertTrue(BarTheme.contrast(t.mut, t.panel) >= AA, "window title on panel");
        assertTrue(BarTheme.contrast(t.ink, t.panel2) >= AA, "hovered label on raised surface");
        assertTrue(BarTheme.contrast(t.ink, t.pop) >= AA, "menu items on popup");
    }

    @Test
    void builtInLightPaletteMeetsAA() {
        BarTheme t = BarTheme.LIGHT;
        assertTrue(BarTheme.contrast(t.ink, t.panel) >= AA, "ink on panel");
        assertTrue(BarTheme.contrast(t.ink2, t.panel) >= AA, "menu labels on panel");
        assertTrue(BarTheme.contrast(t.mut, t.panel) >= AA, "window title on panel");
        assertTrue(BarTheme.contrast(t.ink, t.panel2) >= AA, "hovered label on raised surface");
        assertTrue(BarTheme.contrast(t.ink, t.pop) >= AA, "menu items on popup");
    }

    @Test
    void closeHoverGlyphMeetsAA() {
        assertTrue(BarTheme.contrast(Color.WHITE, new Color(0xc4, 0x2b, 0x1c)) >= AA);
    }

    @Test
    void builtInPalettesAreTheWebToolbarColours() {
        // stagesmith.css: --ss-panel is the .ss-topbar background in each theme
        assertEquals(new Color(0x1a1723), BarTheme.DARK.panel);
        assertEquals(new Color(0xf7f6fb), BarTheme.LIGHT.panel);
        assertEquals(BarTheme.DARK, BarTheme.of(null));
        assertEquals(BarTheme.DARK, BarTheme.of("dark"));
        assertEquals(BarTheme.LIGHT, BarTheme.of(" Light "));
    }

    @Test
    void contrastIsSymmetricAndBounded() {
        assertEquals(21.0, BarTheme.contrast(Color.WHITE, Color.BLACK), 0.01);
        assertEquals(BarTheme.contrast(Color.RED, Color.BLUE), BarTheme.contrast(Color.BLUE, Color.RED), 1e-9);
        assertEquals(1.0, BarTheme.contrast(Color.GRAY, Color.GRAY), 1e-9);
    }

    @Test
    void parsesTheColourFormsGetComputedStyleProduces() {
        assertEquals(new Color(0x1a1723), BarTheme.parseCss(" #1a1723 "));
        assertEquals(new Color(0xffaa00), BarTheme.parseCss("#FA0"));
        assertEquals(new Color(0x1a1723), BarTheme.parseCss("#1a1723ff"));
        assertEquals(new Color(26, 23, 35), BarTheme.parseCss("rgb(26, 23, 35)"));
        assertEquals(new Color(26, 23, 35), BarTheme.parseCss("rgba(26, 23, 35, 0.5)"));
        assertEquals(new Color(26, 23, 35), BarTheme.parseCss("rgb(26 23 35 / 50%)"));
        assertNull(BarTheme.parseCss(null));
        assertNull(BarTheme.parseCss(""));
        assertNull(BarTheme.parseCss("transparent"));
        assertNull(BarTheme.parseCss("var(--ss-panel)"));
        assertNull(BarTheme.parseCss("rgb(300, 0, 0)"));
        assertNull(BarTheme.parseCss("#12"));
    }

    @Test
    void pageReportOverridesEveryColourItResolved() {
        JsonObject o = new JsonObject();
        o.addProperty("theme", "light");
        o.addProperty("panel", "rgb(250, 250, 250)");
        o.addProperty("line", "#cccccc");
        o.addProperty("ink", "#101010");
        BarTheme t = BarTheme.fromPage(o);
        assertTrue(t.light);
        assertEquals(new Color(250, 250, 250), t.panel);
        assertEquals(new Color(0xcccccc), t.line);
        assertEquals(new Color(0x101010), t.ink);
        // unresolved → the built-in colour of the SAME theme
        assertEquals(BarTheme.LIGHT.panel2, t.panel2);
        assertEquals(BarTheme.LIGHT.mut, t.mut);
    }

    @Test
    void unparsableOrMissingReportFallsBackToTheBuiltInPalette() {
        JsonObject o = new JsonObject();
        o.addProperty("theme", "dark");
        o.addProperty("panel", "");          // CSS not loaded yet
        o.addProperty("ink", "var(--x)");    // nonsense
        BarTheme t = BarTheme.fromPage(o);
        assertFalse(t.light);
        assertEquals(BarTheme.DARK.panel, t.panel);
        assertEquals(BarTheme.DARK.ink, t.ink);
        assertEquals(BarTheme.DARK.ink, BarTheme.fromPage(new JsonObject()).ink);
        assertEquals(BarTheme.DARK.ink, BarTheme.fromPage(null).ink);
    }

    /* ---------------- resize geometry ---------------- */

    @Test
    void edgeDetectionCoversCornersEdgesAndInside() {
        int w = 800, h = 600, e = 4;
        assertEquals(Cursor.NW_RESIZE_CURSOR, ResizeBorder.edgeAt(1, 1, w, h, e));
        assertEquals(Cursor.NE_RESIZE_CURSOR, ResizeBorder.edgeAt(798, 2, w, h, e));
        assertEquals(Cursor.SW_RESIZE_CURSOR, ResizeBorder.edgeAt(0, 599, w, h, e));
        assertEquals(Cursor.SE_RESIZE_CURSOR, ResizeBorder.edgeAt(797, 597, w, h, e));
        assertEquals(Cursor.N_RESIZE_CURSOR, ResizeBorder.edgeAt(400, 3, w, h, e));
        assertEquals(Cursor.S_RESIZE_CURSOR, ResizeBorder.edgeAt(400, 596, w, h, e));
        assertEquals(Cursor.W_RESIZE_CURSOR, ResizeBorder.edgeAt(2, 300, w, h, e));
        assertEquals(Cursor.E_RESIZE_CURSOR, ResizeBorder.edgeAt(799, 300, w, h, e));
        assertEquals(0, ResizeBorder.edgeAt(4, 4, w, h, e));
        assertEquals(0, ResizeBorder.edgeAt(400, 300, w, h, e));
    }

    @Test
    void resizingKeepsTheOppositeEdgeAndTheMinimumSize() {
        Rectangle start = new Rectangle(100, 100, 1280, 720);
        Dimension min = new Dimension(980, 640);
        assertEquals(new Rectangle(100, 100, 1380, 720), ResizeBorder.resized(start, Cursor.E_RESIZE_CURSOR, 100, 0, min));
        assertEquals(new Rectangle(100, 100, 1280, 800), ResizeBorder.resized(start, Cursor.S_RESIZE_CURSOR, 0, 80, min));
        // dragging the left edge right by 100 shrinks the width, right edge fixed at 1380
        assertEquals(new Rectangle(200, 100, 1180, 720), ResizeBorder.resized(start, Cursor.W_RESIZE_CURSOR, 100, 0, min));
        // dragging the top edge down by 50 shrinks the height, bottom edge fixed at 820
        assertEquals(new Rectangle(100, 150, 1280, 670), ResizeBorder.resized(start, Cursor.N_RESIZE_CURSOR, 0, 50, min));
        // corner moves both
        assertEquals(new Rectangle(100, 100, 1300, 740), ResizeBorder.resized(start, Cursor.SE_RESIZE_CURSOR, 20, 20, min));
        // never below the minimum — and the fixed edge still stays put
        assertEquals(new Rectangle(100, 100, 980, 640), ResizeBorder.resized(start, Cursor.SE_RESIZE_CURSOR, -900, -900, min));
        assertEquals(new Rectangle(400, 180, 980, 640), ResizeBorder.resized(start, Cursor.NW_RESIZE_CURSOR, 900, 900, min));
    }
}
