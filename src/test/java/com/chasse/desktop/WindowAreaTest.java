package com.chasse.desktop;

import org.junit.jupiter.api.Test;

import java.awt.Insets;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The maximize-keeps-the-taskbar rule: the usable area of a monitor is the
 * monitor minus the taskbar insets, in monitor-relative coordinates (what
 * {@code JFrame.setMaximizedBounds} expects). The window plumbing around it
 * needs a display and a webview, so only the arithmetic is unit-tested.
 */
class WindowAreaTest {

    @Test
    void bottomTaskbarShortensTheArea() {
        Rectangle monitor = new Rectangle(0, 0, 1920, 1080);
        assertEquals(new Rectangle(0, 0, 1920, 1032), Main.usableArea(monitor, new Insets(0, 0, 48, 0)));
    }

    @Test
    void sideAndTopBarsMoveTheOrigin() {
        Rectangle monitor = new Rectangle(0, 0, 2560, 1440);
        assertEquals(new Rectangle(60, 30, 2500, 1410), Main.usableArea(monitor, new Insets(30, 60, 0, 0)));
    }

    @Test
    void secondMonitorStaysMonitorRelative() {
        // monitor placed right of the primary: the area's origin is NOT the monitor's screen x
        Rectangle monitor = new Rectangle(1920, 0, 1600, 900);
        assertEquals(new Rectangle(0, 0, 1600, 860), Main.usableArea(monitor, new Insets(0, 0, 40, 0)));
    }

    @Test
    void noInsetsMeansTheWholeMonitor() {
        Rectangle monitor = new Rectangle(0, 0, 1280, 720);
        assertEquals(new Rectangle(0, 0, 1280, 720), Main.usableArea(monitor, new Insets(0, 0, 0, 0)));
    }

    // ── which keys leave fullscreen (the webview has focus, the title bar is hidden) ──

    private static final int ESC = 27, F11 = 122, A = 65, CTRL = 1 << 2, ALT = 1 << 3;

    @Test
    void escAndPlainF11LeaveFullscreen() {
        assertEquals(Main.FsKey.EXIT, Main.fullscreenKeyAction(ESC, 0, true));
        assertEquals(Main.FsKey.EXIT, Main.fullscreenKeyAction(F11, 0, true));
    }

    @Test
    void escAndPlainF11BelongToThePageOutsideFullscreen() {
        assertEquals(Main.FsKey.NONE, Main.fullscreenKeyAction(ESC, 0, false));
        assertEquals(Main.FsKey.NONE, Main.fullscreenKeyAction(F11, 0, false));
    }

    @Test
    void ctrlF11TogglesBothWays() {
        assertEquals(Main.FsKey.TOGGLE, Main.fullscreenKeyAction(F11, CTRL, false));
        assertEquals(Main.FsKey.TOGGLE, Main.fullscreenKeyAction(F11, CTRL, true));
    }

    @Test
    void altCombinationsAndOtherKeysAreIgnored() {
        assertEquals(Main.FsKey.NONE, Main.fullscreenKeyAction(F11, ALT, true));
        assertEquals(Main.FsKey.NONE, Main.fullscreenKeyAction(F11, CTRL | ALT, true));
        assertEquals(Main.FsKey.NONE, Main.fullscreenKeyAction(ESC, CTRL, true));
        assertEquals(Main.FsKey.NONE, Main.fullscreenKeyAction(A, 0, true));
        assertEquals(Main.FsKey.NONE, Main.fullscreenKeyAction(A, CTRL, false));
    }

    @Test
    void absurdInsetsNeverProduceAnEmptyArea() {
        Rectangle area = Main.usableArea(new Rectangle(0, 0, 100, 100), new Insets(80, 80, 80, 80));
        assertEquals(1, area.width);
        assertEquals(1, area.height);
    }
}
