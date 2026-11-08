package com.chasse.desktop;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.AbstractBorder;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The native dialogs, in the web app's own chrome: a rounded popup-coloured
 * card with a 1 px line-strong edge (rounded by the OS where it can —
 * {@link WindowChrome} — clipped by hand elsewhere), a title in primary ink,
 * the message in secondary ink, an optional input field, and the buttons
 * bottom-right: a quiet one and an accent one (red for destructive
 * questions). Enter is the primary button, Esc the quiet one; the card can
 * be dragged. While it is open a translucent veil dims the owner window,
 * like the web app's own modals.
 *
 * <p>Replaces every {@code JOptionPane} the shell used to show — those came
 * in the Windows look-and-feel's own grey, with a title bar of their own,
 * and looked like another decade next to the web app.</p>
 *
 * <p>Thread-safe: a call from off the EDT builds and shows the dialog on the
 * EDT and waits for the answer; a call on the EDT runs a nested event loop
 * (what a modal dialog is).</p>
 */
final class Dialogs {

    /** card width — enough for a URL, narrow enough to read */
    private static final int WIDTH = 440;
    private static final int PAD = 24;
    /** the card corners and the transparent shadow margin around the body (shifted a little down) */
    private static final int RADIUS = 12;
    private static final int SHADOW = 16;
    private static final int SHADOW_DOWN = 4;
    private static final int BTN_H = 32;
    private static final int BTN_RADIUS = 8;
    private static final int FIELD_RADIUS = 8;

    private final Supplier<Window> owner;
    private final Supplier<BarTheme> theme;

    Dialogs(Supplier<Window> owner, Supplier<BarTheme> theme) {
        this.owner = owner;
        this.theme = theme;
    }

    /* ------------------------------------------------------------------ */
    /* the public dialogs                                                  */
    /* ------------------------------------------------------------------ */

    /** A message with one "OK". */
    void info(String title, String message) {
        show(new Spec(title, message, null, null, "OK", null, false, false));
    }

    /** A message with one "OK" and an icon beside the title (the About box). */
    void info(String title, String message, Image icon) {
        show(new Spec(title, message, icon, null, "OK", null, false, false));
    }

    /** A yes/no question; {@code destructive} paints the primary button red. */
    boolean confirm(String title, String message, String yes, String no, boolean destructive) {
        Result r = show(new Spec(title, message, null, null, yes, no, destructive, false));
        return r.accepted;
    }

    /** A one-line input; null when cancelled. */
    String prompt(String title, String message, String initial, String ok, String cancel) {
        Result r = show(new Spec(title, message, null, initial == null ? "" : initial, ok, cancel, false, true));
        return r.accepted ? r.text : null;
    }

    /* ------------------------------------------------------------------ */
    /* fonts                                                               */
    /* ------------------------------------------------------------------ */

    private static volatile String uiFamily;

    /**
     * The UI face: Segoe UI (the web app's own fallback face on Windows)
     * when the system has it, the logical sans-serif otherwise.
     */
    static Font ui(int style, float size) {
        String fam = uiFamily;
        if (fam == null) {
            fam = Font.SANS_SERIF;
            try {
                String[] all = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
                if (Arrays.asList(all).contains("Segoe UI")) fam = "Segoe UI";
            } catch (Throwable ignored) {
                // headless or odd font setup — logical face
            }
            uiFamily = fam;
        }
        return new Font(fam, style, Math.round(size));
    }

    /** The desktop's text anti-aliasing hints (what Swing's own text painting uses). */
    static void textHints(Graphics2D g2) {
        Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
        if (hints instanceof Map<?, ?> m) {
            g2.addRenderingHints(m);
        } else {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }
    }

    /* ------------------------------------------------------------------ */
    /* building                                                            */
    /* ------------------------------------------------------------------ */

    private record Spec(String title, String message, Image icon, String input,
                        String primary, String secondary, boolean destructive, boolean hasInput) {}

    private static final class Result {
        boolean accepted;
        String text = "";
    }

    private Result show(Spec spec) {
        if (SwingUtilities.isEventDispatchThread()) {
            return showOnEdt(spec);
        }
        AtomicReference<Result> out = new AtomicReference<>(new Result());
        try {
            SwingUtilities.invokeAndWait(() -> out.set(showOnEdt(spec)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            // a painting hiccup must not take the caller down — treat as cancelled
        }
        return out.get();
    }

    private Result showOnEdt(Spec spec) {
        BarTheme t = theme.get() == null ? BarTheme.DARK : theme.get();
        Window own = owner.get();
        if (own != null && !own.isShowing()) own = null;
        Result result = new Result();

        JDialog d = own == null ? new JDialog((Window) null, Dialog.ModalityType.APPLICATION_MODAL)
                                : new JDialog(own, Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        d.setTitle(spec.title);
        d.setBackground(new Color(0, 0, 0, 0));
        d.setResizable(false);
        if (own != null) d.setIconImages(own.getIconImages());

        Card card = new Card(t);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(SHADOW - SHADOW_DOWN + PAD - 2, SHADOW + PAD,
                SHADOW + SHADOW_DOWN + PAD - 4, SHADOW + PAD));
        int inner = WIDTH - 2 * PAD;

        // ── title (+ icon) ─────────────────────────────────────────────────
        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.X_AXIS));
        head.setOpaque(false);
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (spec.icon != null) {
            head.add(new IconBadge(spec.icon, t));
            head.add(Box.createHorizontalStrut(12));
        }
        Text title = new Text(spec.title, ui(Font.BOLD, 15), t.ink, inner - (spec.icon == null ? 0 : 40));
        head.add(title);
        head.add(Box.createHorizontalGlue());
        head.setMaximumSize(new Dimension(inner, head.getPreferredSize().height));
        card.add(head);

        // ── message ────────────────────────────────────────────────────────
        if (spec.message != null && !spec.message.isBlank()) {
            card.add(Box.createVerticalStrut(8));
            Text msg = new Text(spec.message, ui(Font.PLAIN, 13), t.ink2, inner);
            msg.setAlignmentX(Component.LEFT_ALIGNMENT);
            card.add(msg);
        }

        // ── input ──────────────────────────────────────────────────────────
        JTextField field = null;
        if (spec.hasInput) {
            card.add(Box.createVerticalStrut(14));
            field = new Field(spec.input, t);
            styleField(field, t);
            field.setAlignmentX(Component.LEFT_ALIGNMENT);
            field.setMaximumSize(new Dimension(inner, field.getPreferredSize().height));
            card.add(field);
        }

        // ── buttons ────────────────────────────────────────────────────────
        card.add(Box.createVerticalStrut(20));
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(Box.createHorizontalGlue());
        final JTextField f = field;
        Runnable accept = () -> {
            result.accepted = true;
            result.text = f == null ? "" : f.getText();
            d.dispose();
        };
        Runnable cancel = () -> {
            result.accepted = false;
            d.dispose();
        };
        if (spec.secondary != null) {
            row.add(new Btn(spec.secondary, Btn.Kind.QUIET, t, cancel));
            row.add(Box.createHorizontalStrut(8));
        }
        Btn primary = new Btn(spec.primary, spec.destructive ? Btn.Kind.DANGER : Btn.Kind.ACCENT, t, accept);
        row.add(primary);
        row.setMaximumSize(new Dimension(inner, BTN_H));
        card.add(row);

        // Enter = primary, Esc = the quiet way out (or OK when there is only one button)
        JRootPane root = d.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "accept");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        root.getActionMap().put("accept", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { accept.run(); }
        });
        root.getActionMap().put("cancel", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                if (spec.secondary == null) accept.run(); else cancel.run();
            }
        });

        // drag anywhere on the card
        MouseAdapter drag = new MouseAdapter() {
            Point start;
            Point origin;
            @Override public void mousePressed(MouseEvent e) { start = e.getLocationOnScreen(); origin = d.getLocation(); }
            @Override public void mouseReleased(MouseEvent e) { start = null; }
            @Override public void mouseDragged(MouseEvent e) {
                if (start == null) return;
                Point p = e.getLocationOnScreen();
                d.setLocation(origin.x + p.x - start.x, origin.y + p.y - start.y);
            }
        };
        card.addMouseListener(drag);
        card.addMouseMotionListener(drag);

        d.setContentPane(card);
        card.setPreferredSize(new Dimension(WIDTH + 2 * SHADOW, card.getPreferredSize().height));
        d.pack();
        d.setLocationRelativeTo(own);
        if (own == null) centreOnScreen(d);

        boolean rounded = WindowChrome.available() && !(own instanceof java.awt.Frame fr && fr.getExtendedState() == java.awt.Frame.MAXIMIZED_BOTH);
        Veil veil = own == null ? null : new Veil(own, t, rounded);
        if (veil != null) veil.setVisible(true);
        try {
            if (f != null) {
                f.selectAll();
                SwingUtilities.invokeLater(f::requestFocusInWindow);
            } else {
                SwingUtilities.invokeLater(primary::requestFocusInWindow);
            }
            d.setVisible(true); // modal — returns when disposed
        } finally {
            if (veil != null) veil.dispose();
        }
        return result;
    }

    private static void centreOnScreen(Window w) {
        Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        w.setLocation(b.x + (b.width - w.getWidth()) / 2, b.y + (b.height - w.getHeight()) / 2);
    }

    private static void styleField(JTextField field, BarTheme t) {
        field.setFont(ui(Font.PLAIN, 13));
        field.setForeground(t.ink);
        field.setCaretColor(t.ink);
        field.setBackground(t.bg);
        field.setSelectionColor(t.accSoft);
        field.setSelectedTextColor(t.ink);
        field.setOpaque(false);
        field.setBorder(new FieldBorder(t));
        field.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { field.repaint(); }
            @Override public void focusLost(FocusEvent e) { field.repaint(); }
        });
    }

    /* ------------------------------------------------------------------ */
    /* parts                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * The card: in a per-pixel translucent window, a soft shadow (mostly
     * below — the web's {@code --ss-frame-sh}, scaled), the rounded
     * popup-coloured body and its 1 px line-strong edge; everything outside
     * is transparent. The same construction as the title bar's dropdowns.
     */
    private static final class Card extends JPanel {
        private final BarTheme t;

        Card(BarTheme t) {
            this.t = t;
            setOpaque(false);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                float bx = SHADOW, by = SHADOW - SHADOW_DOWN;
                float bw = getWidth() - 2 * SHADOW, bh = getHeight() - 2 * SHADOW;
                int r = 2 * RADIUS;
                Color sh = t.light ? new Color(40, 30, 80) : Color.BLACK;
                float total = t.light ? 0.22f : 0.5f;
                for (int k = SHADOW; k >= 1; k--) {
                    float a = total * 2f * (SHADOW - k + 1) / (SHADOW * (SHADOW + 1f));
                    g2.setColor(new Color(sh.getRed(), sh.getGreen(), sh.getBlue(), Math.round(a * 255)));
                    g2.fill(new RoundRectangle2D.Float(bx - k, by - k + SHADOW_DOWN, bw + 2 * k, bh + 2 * k,
                            r + 2 * k, r + 2 * k));
                }
                g2.setColor(t.pop);
                g2.fill(new RoundRectangle2D.Float(bx, by, bw, bh, r, r));
                g2.setColor(t.lineStrong);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(bx + 0.5f, by + 0.5f, bw - 1, bh - 1, r - 1, r - 1));
            } finally {
                g2.dispose();
            }
        }
    }

    /** Wrapped text at a fixed width, painted in one colour (the title or the message). */
    private static final class Text extends JTextArea {
        Text(String text, Font font, Color color, int width) {
            super(text);
            setFont(font);
            setForeground(color);
            setEditable(false);
            setFocusable(false);
            setOpaque(false);
            setLineWrap(true);
            setWrapStyleWord(true);
            setBorder(BorderFactory.createEmptyBorder());
            setHighlighter(null);
            setCursor(Cursor.getDefaultCursor());
            // let the wrapped height be computed for THIS width
            setSize(width, Short.MAX_VALUE);
            Dimension pref = new Dimension(width, getPreferredSize().height);
            setPreferredSize(pref);
            setMaximumSize(pref);
            setMinimumSize(pref);
        }

        @Override protected void paintComponent(Graphics g) {
            textHints((Graphics2D) g);
            super.paintComponent(g);
        }
    }

    /** The app logo in a rounded 28 px tile (the About box). */
    private static final class IconBadge extends JComponent {
        private static final int S = 28;
        private final Image img;
        private final BarTheme t;

        IconBadge(Image img, BarTheme t) {
            this.img = img;
            this.t = t;
            Dimension d = new Dimension(S, S);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setClip(new RoundRectangle2D.Float(0, 0, S, S, 8, 8));
                g2.setColor(t.panel2);
                g2.fillRect(0, 0, S, S);
                g2.drawImage(img, 0, 0, S, S, this);
            } finally {
                g2.dispose();
            }
        }
    }

    /** A text field with a rounded background in the page colour (painted UNDER the text — a border paints over it). */
    private static final class Field extends JTextField {
        private final BarTheme t;

        Field(String text, BarTheme t) {
            super(text);
            this.t = t;
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(t.bg);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 2 * FIELD_RADIUS, 2 * FIELD_RADIUS));
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** The input field's edge: rounded, line colour at rest, the accent when focused. */
    private static final class FieldBorder extends AbstractBorder {
        private final BarTheme t;

        FieldBorder(BarTheme t) {
            this.t = t;
        }

        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                int r = 2 * FIELD_RADIUS;
                boolean focus = c.isFocusOwner();
                g2.setColor(focus ? t.acc : t.line);
                g2.setStroke(new BasicStroke(focus ? 1.5f : 1f));
                g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, r - 1, r - 1));
            } finally {
                g2.dispose();
            }
        }

        @Override public Insets getBorderInsets(Component c) {
            return new Insets(8, 11, 8, 11);
        }

        @Override public Insets getBorderInsets(Component c, Insets in) {
            in.set(8, 11, 8, 11);
            return in;
        }
    }

    /**
     * A button in the web app's {@code .ss-btn} shape: 32 px, 8 px corners.
     * QUIET = raised surface with a line edge (hover: the hover line);
     * ACCENT = the accent fill with accent ink, semibold; DANGER = the same
     * in the danger colour. Keyboard focus draws a soft accent ring.
     */
    static final class Btn extends JComponent {
        enum Kind { QUIET, ACCENT, DANGER }

        private final String text;
        private final Kind kind;
        private final BarTheme t;
        private final Runnable action;
        private boolean hover;
        private boolean pressed;

        Btn(String text, Kind kind, BarTheme t, Runnable action) {
            this.text = text;
            this.kind = kind;
            this.t = t;
            this.action = action;
            setFont(ui(kind == Kind.QUIET ? Font.PLAIN : Font.BOLD, 13));
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            FontMetrics fm = getFontMetrics(getFont());
            int w = Math.max(84, fm.stringWidth(text) + 2 * 16);
            Dimension d = new Dimension(w, BTN_H);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; pressed = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) { pressed = true; requestFocusInWindow(); repaint(); }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    boolean fire = pressed && contains(e.getPoint());
                    pressed = false;
                    repaint();
                    if (fire) action.run();
                }
            });
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent e) { repaint(); }
                @Override public void focusLost(FocusEvent e) { repaint(); }
            });
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "press");
            getActionMap().put("press", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
            });
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                int w = getWidth();
                int h = getHeight();
                int r = 2 * BTN_RADIUS;
                Color fill;
                Color edge;
                Color ink;
                switch (kind) {
                    case ACCENT -> { fill = t.acc; edge = t.acc; ink = t.accInk; }
                    case DANGER -> { fill = t.danger; edge = t.danger; ink = t.light ? Color.WHITE : new Color(0x2a0a10); }
                    default -> { fill = t.panel2; edge = hover ? t.hovline : t.line; ink = t.ink; }
                }
                if (kind != Kind.QUIET && hover) fill = brighten(fill, pressed ? -0.06f : 0.07f);
                if (kind == Kind.QUIET && pressed) fill = t.accSoft;
                // the web's :active scale(.97), as a 1 px inset
                int in = pressed ? 1 : 0;
                g2.setColor(fill);
                g2.fill(new RoundRectangle2D.Float(in, in, w - 2 * in, h - 2 * in, r, r));
                g2.setColor(edge);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(in + 0.5f, in + 0.5f, w - 1 - 2 * in, h - 1 - 2 * in, r - 1, r - 1));
                if (isFocusOwner()) {
                    g2.setColor(new Color(t.acc.getRed(), t.acc.getGreen(), t.acc.getBlue(), 110));
                    g2.setStroke(new BasicStroke(2f));
                    g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 3, r - 2, r - 2));
                }
                textHints(g2);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                g2.setColor(ink);
                g2.drawString(text, (w - fm.stringWidth(text)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }

        private static Color brighten(Color c, float by) {
            float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
            return Color.getHSBColor(hsb[0], hsb[1], Math.max(0f, Math.min(1f, hsb[2] + by)));
        }
    }

    /**
     * The web app's {@code --ss-veil} over the owner window while a dialog
     * is up: a translucent, non-focusable window the size of the owner. Its
     * own top-level window, so it covers the embedded browser too.
     */
    private static final class Veil extends JWindow {
        Veil(Window owner, BarTheme t, boolean rounded) {
            super(owner);
            setFocusableWindowState(false);
            setBackground(new Color(0, 0, 0, 0));
            JPanel p = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    try {
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g2.setColor(t.light ? new Color(40, 30, 80, 90) : new Color(8, 6, 14, 160));
                        // the owner's outline: the OS rounds a normal window by 8 px on Windows 11
                        int r = rounded ? 2 * WindowChrome.FRAME_RADIUS : 0;
                        g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), r, r));
                    } finally {
                        g2.dispose();
                    }
                }
            };
            p.setOpaque(false);
            setContentPane(p);
            setBounds(owner.getBounds());
        }
    }

    /* ------------------------------------------------------------------ */
    /* the fullscreen pill                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * A floating rounded pill ("Exit full screen") — its own translucent,
     * non-focusable window: the rounded popup-coloured body with a
     * line-strong edge and a soft shadow, the text in primary ink, an accent
     * tint on hover. Clicking runs {@code action}.
     */
    static JWindow pill(Window owner, BarTheme t, String text, Runnable action) {
        JWindow w = new JWindow(owner);
        w.setFocusableWindowState(false);
        w.setBackground(new Color(0, 0, 0, 0));
        final int sh = 10;      // shadow margin
        final int padX = 18;
        final int h = 36;
        JComponent body = new JComponent() {
            boolean hover;
            {
                setOpaque(false);
                setFont(ui(Font.PLAIN, 13));
                FontMetrics fm = getFontMetrics(getFont());
                setPreferredSize(new Dimension(fm.stringWidth(text) + 22 + 2 * padX + 2 * sh, h + 2 * sh));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                    @Override public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e)) action.run();
                    }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                    int bw = getWidth() - 2 * sh;
                    int bh = h;
                    Color shc = t.light ? new Color(40, 30, 80) : Color.BLACK;
                    float total = t.light ? 0.18f : 0.42f;
                    for (int k = sh; k >= 1; k--) {
                        float a = total * 2f * (sh - k + 1) / (sh * (sh + 1f));
                        g2.setColor(new Color(shc.getRed(), shc.getGreen(), shc.getBlue(), Math.round(a * 255)));
                        g2.fill(new RoundRectangle2D.Float(sh - k, sh - k + 3, bw + 2 * k, bh + 2 * k, bh + 2 * k, bh + 2 * k));
                    }
                    g2.setColor(hover ? t.accSoft : t.pop);
                    g2.fill(new RoundRectangle2D.Float(sh, sh, bw, bh, bh, bh));
                    g2.setColor(t.lineStrong);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(new RoundRectangle2D.Float(sh + 0.5f, sh + 0.5f, bw - 1, bh - 1, bh - 1, bh - 1));
                    textHints(g2);
                    g2.setFont(getFont());
                    FontMetrics fm = g2.getFontMetrics();
                    // a drawn ✕ (the close button's glyph — Segoe UI has no U+2715), then the text
                    float cx = sh + padX + 5f, cy = sh + bh / 2f;
                    g2.setColor(t.ink2);
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.draw(new java.awt.geom.Line2D.Float(cx - 4, cy - 4, cx + 4, cy + 4));
                    g2.draw(new java.awt.geom.Line2D.Float(cx + 4, cy - 4, cx - 4, cy + 4));
                    g2.setColor(t.ink);
                    g2.drawString(text, sh + padX + 10 + 12, sh + (bh - fm.getHeight()) / 2 + fm.getAscent());
                } finally {
                    g2.dispose();
                }
            }
        };
        w.setContentPane(body);
        w.pack();
        return w;
    }
}
