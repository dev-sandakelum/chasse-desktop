package com.chasse.desktop;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * The native title bar — minimal, flat, 32 px, in the web app's own chrome
 * palette so it continues the web toolbar directly beneath it (no border, no
 * gradient, no shadow: the two surfaces are the same colour).
 *
 * <pre>
 *  [logo]  File  View  Help          window title (muted)          ─   □   ✕
 * </pre>
 *
 * <p>No wordmark or tagline here — the web toolbar already carries the brand.
 * The bar is the drag handle (double-click maximizes / restores, like any
 * window), and {@link #applyTheme} restyles everything in place whenever the
 * page reports a theme change ({@code ui.theme}).</p>
 */
final class TitleBar extends JPanel {

    static final int HEIGHT = 32;

    /** the close button's hover — the one colour not taken from the web palette (white glyph on it: 5.7:1) */
    private static final Color CLOSE_HOVER = new Color(0xc4, 0x2b, 0x1c);

    /** what the menus do — the window logic stays in Main */
    record Actions(Runnable changeAppUrl, Runnable reload, Runnable exit,
                   Runnable toggleFullscreen, Runnable about, Runnable toggleMaximize) {}

    private volatile BarTheme theme;
    private final JFrame frame;
    private final JLabel title;
    private final JMenuBar menuBar;
    private final List<JMenu> menus = new ArrayList<>();
    private final List<JMenuItem> items = new ArrayList<>();
    private final List<JPanel> separators = new ArrayList<>();

    private Point dragOrigin;
    private Point frameOrigin;

    TitleBar(JFrame frame, BarTheme initial, Image logo, Actions actions) {
        super(new BorderLayout());
        this.frame = frame;
        this.theme = initial;
        setOpaque(true);
        setPreferredSize(new Dimension(0, HEIGHT));
        setBorder(BorderFactory.createEmptyBorder());

        // ── left: logo + quiet text menus ───────────────────────────────────
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        left.add(new Logo(logo));
        left.add(gap(8));
        menuBar = buildMenuBar(actions);
        left.add(menuBar);

        // ── centre: the window title, muted ──────────────────────────────────
        title = new JLabel("", SwingConstants.CENTER);
        title.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        title.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));

        // ── right: thin window controls ──────────────────────────────────────
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(new WinButton(WinButton.Kind.MINIMIZE,
                () -> frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED)));
        right.add(new WinButton(WinButton.Kind.MAXIMIZE, actions.toggleMaximize()));
        right.add(new WinButton(WinButton.Kind.CLOSE, actions.exit()));

        add(left, BorderLayout.WEST);
        add(title, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);

        // drag-to-move (not while maximized), double-click = maximize / restore
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (frame.getExtendedState() == Frame.MAXIMIZED_BOTH) return;
                dragOrigin = e.getLocationOnScreen();
                frameOrigin = frame.getLocation();
            }
            @Override public void mouseReleased(MouseEvent e) { dragOrigin = null; }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) actions.toggleMaximize().run();
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) {
                if (dragOrigin == null) return;
                Point cur = e.getLocationOnScreen();
                frame.setLocation(frameOrigin.x + cur.x - dragOrigin.x, frameOrigin.y + cur.y - dragOrigin.y);
            }
        });
        setCursor(Cursor.getDefaultCursor());
        applyTheme(initial);
    }

    BarTheme theme() {
        return theme;
    }

    /** The document title of the page (CEF onTitleChange) — shown muted in the centre. */
    void setTitle(String text) {
        String t = text == null ? "" : text.trim();
        title.setText(t);
        frame.setTitle(t.isEmpty() ? Main.APP_NAME : t); // taskbar / Alt-Tab
    }

    /** Hidden in fullscreen. */
    void setBarVisible(boolean v) {
        setVisible(v);
    }

    /** Restyles the bar, menus and popups in place (EDT). */
    void applyTheme(BarTheme t) {
        theme = t;
        setBackground(t.panel);
        title.setForeground(t.mut);
        frame.getContentPane().setBackground(t.panel);
        frame.setBackground(t.panel);

        // menu hover/open colours live in the L&F defaults — re-put them, then
        // re-install the UIs so the change is picked up
        UIManager.put("Menu.selectionBackground", t.panel2);
        UIManager.put("Menu.selectionForeground", t.ink);
        UIManager.put("MenuItem.selectionBackground", t.panel2);
        UIManager.put("MenuItem.selectionForeground", t.ink);
        UIManager.put("MenuItem.acceleratorForeground", t.mut);
        UIManager.put("MenuItem.acceleratorSelectionForeground", t.ink);
        for (JMenu m : menus) {
            m.updateUI();
            m.setForeground(t.ink2);
            m.setBackground(t.panel);
            m.setOpaque(false);
            m.setBorderPainted(false);
            m.getPopupMenu().setBackground(t.pop);
        }
        for (JMenuItem i : items) {
            i.updateUI();
            i.setForeground(t.ink);
            i.setBackground(t.pop);
            i.setOpaque(true);
            i.setBorderPainted(false);
        }
        for (JPanel s : separators) s.repaint();
        repaint();
    }

    /* ------------------------------------------------------------------ */
    /* menus                                                               */
    /* ------------------------------------------------------------------ */

    private JMenuBar buildMenuBar(Actions a) {
        Font menuFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);

        JMenu file = menu("File", menuFont);
        file.add(item("Change app URL…", menuFont, a.changeAppUrl()));
        file.add(item("Reload", menuFont, a.reload()));
        file.getPopupMenu().add(separator());
        file.add(item("Exit", menuFont, a.exit()));

        JMenu view = menu("View", menuFont);
        JMenuItem fs = item("Toggle Fullscreen", menuFont, a.toggleFullscreen());
        fs.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F11, KeyEvent.CTRL_DOWN_MASK));
        view.add(fs);

        JMenu help = menu("Help", menuFont);
        help.add(item("About", menuFont, a.about()));

        JMenuBar bar = new JMenuBar() {
            @Override protected void paintComponent(Graphics g) { /* transparent — the bar paints */ }
            @Override protected void paintBorder(Graphics g) { /* none */ }
        };
        bar.setOpaque(false);
        bar.setBorder(BorderFactory.createEmptyBorder());
        bar.add(file);
        bar.add(view);
        bar.add(help);
        // the menu row is as tall as the bar, so the labels centre vertically
        // (the menus stretch to it — their text is centred by the UI)
        for (JMenu m : menus) {
            Dimension d = m.getPreferredSize();
            m.setPreferredSize(new Dimension(d.width, HEIGHT));
        }
        Dimension d = bar.getPreferredSize();
        bar.setPreferredSize(new Dimension(d.width, HEIGHT));
        return bar;
    }

    private JMenu menu(String text, Font font) {
        JMenu m = new JMenu(text);
        m.setFont(font);
        m.setMargin(new Insets(0, 2, 0, 2));
        m.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        stylePopup(m.getPopupMenu());
        menus.add(m);
        return m;
    }

    private JMenuItem item(String text, Font font, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(font);
        i.setBorder(BorderFactory.createEmptyBorder(5, 14, 5, 18));
        i.addActionListener(ev -> action.run());
        items.add(i);
        return i;
    }

    /** Flat popup in the popup colour with a 1 px hairline — reads the live theme at paint time. */
    private void stylePopup(JPopupMenu popup) {
        popup.setOpaque(true);
        popup.setUI(new BasicPopupMenuUI() {
            @Override public void paint(Graphics g, JComponent c) {
                g.setColor(theme.pop);
                g.fillRect(0, 0, c.getWidth(), c.getHeight());
                super.paint(g, c);
            }
        });
        popup.setBorder(new AbstractBorder() {
            private static final int PAD = 4;
            @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
                g.setColor(theme.line);
                g.drawRect(x, y, w - 1, h - 1);
            }
            @Override public Insets getBorderInsets(Component c) { return new Insets(PAD, 1, PAD, 1); }
            @Override public Insets getBorderInsets(Component c, Insets insets) { insets.set(PAD, 1, PAD, 1); return insets; }
        });
    }

    private JPanel separator() {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(theme.line);
                g.fillRect(8, getHeight() / 2, getWidth() - 16, 1);
            }
        };
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(0, 7));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 7));
        separators.add(p);
        return p;
    }

    /** Spacer — full bar height so the FlowLayout row (which top-aligns) stays the bar's height. */
    private static JPanel gap(int w) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(w, HEIGHT));
        return p;
    }

    /* ------------------------------------------------------------------ */
    /* logo                                                                */
    /* ------------------------------------------------------------------ */

    /** The app logo, 18 px, rounded — the only branding on the bar. */
    private final class Logo extends JPanel {
        private static final int S = 18;
        private final Image logo;

        Logo(Image logo) {
            this.logo = logo;
            setOpaque(false);
            // TitleBar.HEIGHT explicitly — a bare HEIGHT here is the inherited ImageObserver.HEIGHT (= 2)
            setPreferredSize(new Dimension(S, TitleBar.HEIGHT));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int x = (getWidth() - S) / 2;
            int y = (getHeight() - S) / 2;
            if (logo != null) {
                g2.setClip(new java.awt.geom.RoundRectangle2D.Float(x, y, S, S, 5, 5));
                g2.drawImage(logo, x, y, S, S, this);
            } else {
                g2.setColor(theme.ink2);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(x, y, S - 1, S - 1, 5, 5);
            }
            g2.dispose();
        }
    }

    /* ------------------------------------------------------------------ */
    /* window controls                                                     */
    /* ------------------------------------------------------------------ */

    /** ─ □ ✕ as 1 px strokes; close turns red on hover, the others take the raised surface. */
    private final class WinButton extends JPanel {
        enum Kind { MINIMIZE, MAXIMIZE, CLOSE }

        private static final int W = 40;
        private final Kind kind;
        private boolean hovered;

        WinButton(Kind kind, Runnable action) {
            this.kind = kind;
            setOpaque(false);
            setPreferredSize(new Dimension(W, TitleBar.HEIGHT)); // not the inherited ImageObserver.HEIGHT
            setCursor(Cursor.getDefaultCursor());
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) action.run();
                }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            BarTheme t = theme;
            Color fg = t.ink2;
            if (hovered) {
                boolean close = kind == Kind.CLOSE;
                g2.setColor(close ? CLOSE_HOVER : t.panel2);
                g2.fillRect(0, 0, getWidth(), getHeight());
                fg = close ? Color.WHITE : t.ink;
            }
            g2.setColor(fg);
            g2.setStroke(new BasicStroke(1f));
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            switch (kind) {
                case MINIMIZE -> g2.draw(new java.awt.geom.Line2D.Float(cx - 5, cy + 0.5f, cx + 5, cy + 0.5f));
                case MAXIMIZE -> {
                    boolean maximized = frame.getExtendedState() == Frame.MAXIMIZED_BOTH;
                    if (maximized) {
                        // restore: two offset squares
                        g2.draw(new java.awt.geom.Rectangle2D.Float(cx - 2.5f, cy - 4.5f, 7, 7));
                        g2.setColor(hovered && kind != Kind.CLOSE ? t.panel2 : t.panel);
                        g2.fill(new java.awt.geom.Rectangle2D.Float(cx - 4.5f, cy - 2.5f, 7, 7));
                        g2.setColor(fg);
                        g2.draw(new java.awt.geom.Rectangle2D.Float(cx - 4.5f, cy - 2.5f, 7, 7));
                    } else {
                        g2.draw(new java.awt.geom.Rectangle2D.Float(cx - 4.5f, cy - 4.5f, 9, 9));
                    }
                }
                case CLOSE -> {
                    g2.draw(new java.awt.geom.Line2D.Float(cx - 4.5f, cy - 4.5f, cx + 4.5f, cy + 4.5f));
                    g2.draw(new java.awt.geom.Line2D.Float(cx + 4.5f, cy - 4.5f, cx - 4.5f, cy + 4.5f));
                }
            }
            g2.dispose();
        }
    }
}
