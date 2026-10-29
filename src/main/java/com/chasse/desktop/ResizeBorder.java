package com.chasse.desktop;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Resize handles for the undecorated window: the content pane keeps a thin
 * empty border around the title bar and the webview, and dragging that
 * border resizes the frame (the OS gives an undecorated frame no handles of
 * its own). The strip is painted in the bar colour so it reads as the window
 * edge. Edges are switched off while maximized / fullscreen — there is
 * nothing to resize and the strip would show as a gap.
 */
final class ResizeBorder extends JPanel {

    static final int EDGE = 4;

    private final JFrame frame;
    private boolean edges = true;
    private int dir;                 // Cursor.*_RESIZE_CURSOR, or 0 = not on an edge
    private Point dragStart;
    private Rectangle startBounds;

    ResizeBorder(JFrame frame) {
        super(new BorderLayout());
        this.frame = frame;
        setEdges(true);
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                dir = edges ? edgeAt(e.getX(), e.getY(), getWidth(), getHeight(), EDGE) : 0;
                setCursor(Cursor.getPredefinedCursor(dir == 0 ? Cursor.DEFAULT_CURSOR : dir));
            }
            @Override public void mouseExited(MouseEvent e) {
                if (dragStart == null) {
                    setCursor(Cursor.getDefaultCursor());
                }
            }
            @Override public void mousePressed(MouseEvent e) {
                if (dir == 0) return;
                dragStart = e.getLocationOnScreen();
                startBounds = frame.getBounds();
            }
            @Override public void mouseReleased(MouseEvent e) {
                dragStart = null;
                startBounds = null;
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                Point p = e.getLocationOnScreen();
                frame.setBounds(resized(startBounds, dir, p.x - dragStart.x, p.y - dragStart.y, frame.getMinimumSize()));
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    /** on = normal window (thin resize strip); off = maximized / fullscreen (no strip). */
    void setEdges(boolean on) {
        edges = on;
        int e = on ? EDGE : 0;
        setBorder(BorderFactory.createEmptyBorder(e, e, e, e));
        if (!on) {
            dir = 0;
            setCursor(Cursor.getDefaultCursor());
        }
        revalidate();
    }

    /** Which resize cursor a point inside a w×h panel with an {@code edge}-wide rim maps to (0 = none). Pure — unit-tested. */
    static int edgeAt(int x, int y, int w, int h, int edge) {
        boolean l = x < edge;
        boolean r = x >= w - edge;
        boolean t = y < edge;
        boolean b = y >= h - edge;
        if (t && l) return Cursor.NW_RESIZE_CURSOR;
        if (t && r) return Cursor.NE_RESIZE_CURSOR;
        if (b && l) return Cursor.SW_RESIZE_CURSOR;
        if (b && r) return Cursor.SE_RESIZE_CURSOR;
        if (t) return Cursor.N_RESIZE_CURSOR;
        if (b) return Cursor.S_RESIZE_CURSOR;
        if (l) return Cursor.W_RESIZE_CURSOR;
        if (r) return Cursor.E_RESIZE_CURSOR;
        return 0;
    }

    /** The bounds after dragging edge {@code dir} by (dx, dy), never below {@code min}. Pure — unit-tested. */
    static Rectangle resized(Rectangle start, int dir, int dx, int dy, Dimension min) {
        int x = start.x, y = start.y, w = start.width, h = start.height;
        boolean west = dir == Cursor.W_RESIZE_CURSOR || dir == Cursor.NW_RESIZE_CURSOR || dir == Cursor.SW_RESIZE_CURSOR;
        boolean east = dir == Cursor.E_RESIZE_CURSOR || dir == Cursor.NE_RESIZE_CURSOR || dir == Cursor.SE_RESIZE_CURSOR;
        boolean north = dir == Cursor.N_RESIZE_CURSOR || dir == Cursor.NW_RESIZE_CURSOR || dir == Cursor.NE_RESIZE_CURSOR;
        boolean south = dir == Cursor.S_RESIZE_CURSOR || dir == Cursor.SW_RESIZE_CURSOR || dir == Cursor.SE_RESIZE_CURSOR;
        if (east) w = Math.max(min.width, start.width + dx);
        if (south) h = Math.max(min.height, start.height + dy);
        if (west) {
            w = Math.max(min.width, start.width - dx);
            x = start.x + start.width - w;   // the right edge stays put
        }
        if (north) {
            h = Math.max(min.height, start.height - dy);
            y = start.y + start.height - h;  // the bottom edge stays put
        }
        return new Rectangle(x, y, w, h);
    }
}
