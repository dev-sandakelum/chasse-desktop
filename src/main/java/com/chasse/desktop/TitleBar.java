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
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicMenuUI;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *
 * <p>The menus are painted entirely by this class — the menu, item and popup
 * UIs below are {@code Basic*UI} subclasses that read the live theme at paint
 * time, so neither the Windows look-and-feel's Vista menu skin (the white
 * popup background and blue highlight) nor its check-icon gutter ever shows.
 * A dropdown is the web app's {@code .ss-menu-pop}: popup colour, 1 px
 * {@code line-strong} edge, 10 px corners, a soft shadow below, 5 px padding,
 * items with a 6 px-rounded accent-soft hover. Dropdowns are heavyweight
 * popups (real windows, per-pixel translucent) so the corners and the shadow
 * composite over the browser view underneath instead of over a hole cut into
 * it.</p>
 */
final class TitleBar extends JPanel {

    static final int HEIGHT = 32;

    /** the close button's hover — the one colour not taken from the web palette (white glyph on it: 5.7:1) */
    private static final Color CLOSE_HOVER = new Color(0xc4, 0x2b, 0x1c);

    /** popup body: corner radius and inner padding (web: border-radius 10px, padding 5px + the 1 px edge) */
    private static final int POP_RADIUS = 10;
    private static final int POP_PAD = 6;
    /** how far the shadow reaches beyond the body: a little to the sides and above, more below */
    private static final int SH_SIDE = 10;
    private static final int SH_TOP = 4;
    private static final int SH_BOTTOM = 16;
    /** gap between the bar and the popup body */
    private static final int POP_GAP = 2;
    /** item hover radius (web: 6px) and the gap before an accelerator */
    private static final int ITEM_RADIUS = 6;
    private static final int ACCEL_GAP = 28;
    /** separator: 1 px line with the web's 5px 6px margin */
    private static final int SEP_MARGIN_Y = 5;
    private static final int SEP_MARGIN_X = 6;

    /** client property on a popup that should get a translucent window (see {@link TranslucentPopups}) */
    private static final String TRANSLUCENT_WANTED = "chasse.popup.translucentWanted";
    /** client property set once the popup's window is known to be translucent */
    private static final String TRANSLUCENT = "chasse.popup.translucent";

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
        TranslucentPopups.install();

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

    /**
     * Restyles the bar, menus and popups in place (EDT). The menu painters
     * read {@link #theme} every time they paint, so this is a colour swap and
     * a repaint — no UI re-installation (which would hand the popups back to
     * the look-and-feel's painter).
     */
    void applyTheme(BarTheme t) {
        theme = t;
        setBackground(t.panel);
        title.setForeground(t.mut);
        frame.getContentPane().setBackground(t.panel);
        frame.setBackground(t.panel);
        for (JMenu m : menus) {
            m.setForeground(t.ink2);
            JPopupMenu p = m.getPopupMenu();
            p.setBackground(t.pop);
            p.repaint();
        }
        for (JMenuItem i : items) {
            i.setForeground(t.ink2);
            i.setBackground(t.pop);
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
        JMenu m = new JMenu(text) {
            /** the popup's visible body (not its shadow margin) starts at the menu's left edge, a hair below the bar */
            @Override protected Point getPopupMenuOrigin() {
                Point p = super.getPopupMenuOrigin();
                return new Point(p.x - SH_SIDE, p.y - SH_TOP + POP_GAP);
            }
        };
        m.setFont(font);
        m.setUI(new HeaderUI());
        m.setOpaque(false);
        m.setBorderPainted(false);
        m.setMargin(new Insets(0, 2, 0, 2));
        m.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        stylePopup(m.getPopupMenu());
        menus.add(m);
        return m;
    }

    private JMenuItem item(String text, Font font, Runnable action) {
        JMenuItem i = new JMenuItem(text);
        i.setFont(font);
        i.setUI(new ItemUI());
        i.setOpaque(true);
        i.setBorderPainted(false);
        i.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10)); // web: padding 7px 10px
        i.addActionListener(ev -> action.run());
        items.add(i);
        return i;
    }

    /**
     * The dropdown: a heavyweight (own-window) popup whose window is made
     * per-pixel translucent by {@link TranslucentPopups}; {@link PopupUI}
     * paints the shadow, the rounded body and its hairline, and the border is
     * just the room for them.
     */
    private void stylePopup(JPopupMenu popup) {
        popup.setUI(new PopupUI());
        popup.setOpaque(false);
        popup.setLightWeightPopupEnabled(false);
        popup.putClientProperty(TRANSLUCENT_WANTED, Boolean.TRUE);
        popup.setBorder(BorderFactory.createEmptyBorder(
                SH_TOP + POP_PAD, SH_SIDE + POP_PAD, SH_BOTTOM + POP_PAD, SH_SIDE + POP_PAD));
    }

    /** A single 1 px hairline in the line colour, with the web's margins — the popup paints behind it. */
    private JPanel separator() {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(theme.line);
                g.fillRect(SEP_MARGIN_X, getHeight() / 2, getWidth() - 2 * SEP_MARGIN_X, 1);
            }
        };
        p.setOpaque(false);
        int h = 2 * SEP_MARGIN_Y + 1;
        p.setPreferredSize(new Dimension(0, h));
        p.setMinimumSize(new Dimension(0, h));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
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

    /** The desktop's text anti-aliasing hints (what Swing's own text painting uses). */
    private static void textHints(Graphics2D g2) {
        Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (hints instanceof Map<?, ?> m) {
            g2.addRenderingHints(m);
        } else {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }
    }

    private static String acceleratorText(KeyStroke ks) {
        String mods = ks.getModifiers() == 0 ? "" : InputEvent.getModifiersExText(ks.getModifiers()) + "+";
        return mods + KeyEvent.getKeyText(ks.getKeyCode());
    }

    /* ------------------------------------------------------------------ */
    /* menu painters                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * A top-level menu ("File"): quiet secondary-ink text centred in the bar;
     * while open it sits on the raised surface in primary ink — the same
     * hover treatment as the web toolbar's buttons. Behaviour (open on click,
     * slide between open menus, keyboard) is the stock {@link BasicMenuUI}.
     */
    private final class HeaderUI extends BasicMenuUI {
        @Override public void update(Graphics g, JComponent c) { paint(g, c); }

        @Override public void paint(Graphics g, JComponent c) {
            JMenu m = (JMenu) c;
            BarTheme t = theme;
            boolean open = m.isSelected();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = c.getWidth();
                int h = c.getHeight();
                if (open) {
                    g2.setColor(t.panel2);
                    g2.fillRect(0, 0, w, h);
                }
                textHints(g2);
                g2.setFont(m.getFont());
                FontMetrics fm = g2.getFontMetrics();
                int x = (w - fm.stringWidth(m.getText())) / 2;
                int y = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(open ? t.ink : t.ink2);
                g2.drawString(m.getText(), x, y);
            } finally {
                g2.dispose();
            }
        }

        @Override public Dimension getPreferredSize(JComponent c) {
            JMenu m = (JMenu) c;
            FontMetrics fm = m.getFontMetrics(m.getFont());
            Insets in = m.getInsets();
            // + the margin on each side: the same header widths the Windows menu UI
            // laid out before the painters moved here, so the bar does not shift
            Insets mg = m.getMargin();
            int pad = mg == null ? 0 : mg.left + mg.right + 4;
            return new Dimension(in.left + fm.stringWidth(m.getText()) + pad + in.right,
                    in.top + fm.getHeight() + in.bottom);
        }
    }

    /**
     * A dropdown item: popup-coloured, text in secondary ink; armed (hovered
     * or reached with the keyboard — the same state in Swing) it gets the
     * accent-soft rounded fill and primary ink, like the web's menu items. The
     * accelerator sits at the right in muted ink. No check / arrow gutter.
     */
    private final class ItemUI extends BasicMenuItemUI {
        @Override public void update(Graphics g, JComponent c) { paint(g, c); }

        @Override public void paint(Graphics g, JComponent c) {
            JMenuItem mi = (JMenuItem) c;
            BarTheme t = theme;
            boolean hot = mi.getModel().isArmed() || (mi instanceof JMenu && mi.getModel().isSelected());
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = c.getWidth();
                int h = c.getHeight();
                // opaque: an item repaints alone when its armed state flips, so it must cover its old hover
                g2.setColor(t.pop);
                g2.fillRect(0, 0, w, h);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (hot) {
                    g2.setColor(t.accSoft);
                    g2.fill(new RoundRectangle2D.Float(0, 0, w, h, ITEM_RADIUS * 2, ITEM_RADIUS * 2));
                }
                textHints(g2);
                g2.setFont(mi.getFont());
                FontMetrics fm = g2.getFontMetrics();
                Insets in = mi.getInsets();
                int y = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(!mi.isEnabled() ? t.mut : hot ? t.ink : t.ink2);
                g2.drawString(mi.getText(), in.left, y);
                KeyStroke ks = mi.getAccelerator();
                if (ks != null) {
                    String acc = acceleratorText(ks);
                    g2.setColor(hot ? t.ink2 : t.mut);
                    g2.drawString(acc, w - in.right - fm.stringWidth(acc), y);
                }
            } finally {
                g2.dispose();
            }
        }

        @Override public Dimension getPreferredSize(JComponent c) {
            JMenuItem mi = (JMenuItem) c;
            FontMetrics fm = mi.getFontMetrics(mi.getFont());
            Insets in = mi.getInsets();
            int w = fm.stringWidth(mi.getText());
            KeyStroke ks = mi.getAccelerator();
            if (ks != null) w += ACCEL_GAP + fm.stringWidth(acceleratorText(ks));
            return new Dimension(in.left + w + in.right, in.top + fm.getHeight() + in.bottom);
        }
    }

    /**
     * The dropdown surface. In a translucent window: a soft shadow (mostly
     * below), the rounded popup-coloured body and its 1 px line-strong edge,
     * everything else transparent. If the window could not be made
     * translucent (no per-pixel translucency on this display), the whole
     * rectangle is the body with a square edge — still dark, still one line.
     */
    private final class PopupUI extends BasicPopupMenuUI {
        @Override public void update(Graphics g, JComponent c) { paint(g, c); }

        @Override public void paint(Graphics g, JComponent c) {
            BarTheme t = theme;
            int w = c.getWidth();
            int h = c.getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                boolean translucent = Boolean.TRUE.equals(((JComponent) c).getClientProperty(TRANSLUCENT));
                if (!translucent) {
                    g2.setColor(t.pop);
                    g2.fillRect(0, 0, w, h);
                    g2.setColor(t.lineStrong);
                    g2.drawRect(0, 0, w - 1, h - 1);
                    return;
                }
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                // the body: inset by the shadow margins
                float bx = SH_SIDE, by = SH_TOP, bw = w - 2 * SH_SIDE, bh = h - SH_TOP - SH_BOTTOM;

                // shadow: concentric rounded rects, each a little bigger and
                // fainter, centred a few px below the body (web: 0 18px 50px
                // rgba(0,0,0,.45) on dark, a plum tint on light — scaled to a menu)
                Color sh = t.light ? new Color(40, 30, 80) : Color.BLACK;
                float total = t.light ? 0.16f : 0.38f;
                int spread = SH_SIDE;
                float down = (SH_BOTTOM - SH_TOP) / 2f;
                for (int k = spread; k >= 1; k--) {
                    float a = total * 2f * (spread - k + 1) / (spread * (spread + 1f));
                    g2.setColor(new Color(sh.getRed(), sh.getGreen(), sh.getBlue(), Math.round(a * 255)));
                    g2.fill(new RoundRectangle2D.Float(bx - k, by - k + down, bw + 2 * k, bh + 2 * k,
                            2 * (POP_RADIUS + k), 2 * (POP_RADIUS + k)));
                }

                g2.setColor(t.pop);
                g2.fill(new RoundRectangle2D.Float(bx, by, bw, bh, 2 * POP_RADIUS, 2 * POP_RADIUS));
                g2.setColor(t.lineStrong);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(bx + 0.5f, by + 0.5f, bw - 1, bh - 1,
                        2 * POP_RADIUS - 1, 2 * POP_RADIUS - 1));
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * The shared {@link PopupFactory}, wrapped once: a heavyweight popup that
     * asked for it ({@link #TRANSLUCENT_WANTED}) gets its window made
     * per-pixel translucent before it is shown, so the popup's own painting
     * (rounded body, shadow) is composited over whatever is underneath — the
     * browser view included. Every other popup goes through untouched.
     */
    private static final class TranslucentPopups extends PopupFactory {
        private static boolean installed;

        static synchronized void install() {
            if (installed) return;
            installed = true;
            try {
                PopupFactory.setSharedInstance(new TranslucentPopups());
            } catch (RuntimeException ignored) {
                // no translucent popups then — PopupUI paints its square fallback
            }
        }

        @Override public Popup getPopup(Component owner, Component contents, int x, int y) {
            Popup popup = super.getPopup(owner, contents, x, y);
            if (contents instanceof JComponent jc && Boolean.TRUE.equals(jc.getClientProperty(TRANSLUCENT_WANTED))) {
                Window w = SwingUtilities.getWindowAncestor(contents);
                boolean ok = false;
                if (w instanceof JWindow && w.getType() == Window.Type.POPUP) {
                    try {
                        Color bg = w.getBackground();
                        if (bg == null || bg.getAlpha() == 255) w.setBackground(new Color(0, 0, 0, 0));
                        ok = w.getBackground() != null && w.getBackground().getAlpha() == 0;
                    } catch (RuntimeException unsupported) {
                        ok = false;
                    }
                }
                jc.putClientProperty(TRANSLUCENT, ok);
            }
            return popup;
        }
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
