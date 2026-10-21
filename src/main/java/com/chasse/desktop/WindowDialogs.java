package com.chasse.desktop;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefJSDialogCallback;
import org.cef.handler.CefJSDialogHandler;
import org.cef.handler.CefJSDialogHandler.JSDialogType;
import org.cef.handler.CefJSDialogHandlerAdapter;
import org.cef.handler.CefLifeSpanHandler;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.misc.BoolRef;

import java.awt.Desktop;
import java.net.URI;
import java.util.function.Supplier;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The native UX the embedded Chromium window cannot provide on its own:
 *
 * <ul>
 *   <li><b>JavaScript dialogs</b> — JCEF never renders alert()/confirm()/
 *       prompt() unless the client implements {@link CefJSDialogHandler};
 *       without this handler they silently vanish (calls return undefined).
 *       This class routes them to native Swing dialogs on the EDT. Blocking
 *       semantics are preserved: the JS thread waits until the user answers
 *       (that is what a modal dialog is).</li>
 *   <li><b>window.open / target="_blank"</b> — JCEF's default popup handling
 *       tries to create a raw JCEF popup window with no sensible chrome and
 *       no history; instead the URL is opened in the user's system browser
 *       ({@link java.awt.Desktop#browse}) and the popup itself is cancelled.</li>
 *   <li><b>beforeunload confirmations</b> — the page's unload guard (unsaved
 *       edits) gets a real native Yes/No instead of silently dropping the
 *       dialog.</li>
 * </ul>
 */
public final class WindowDialogs {

    private WindowDialogs() {
    }

    /* ------------------------------------------------------------------ */
    /* JS dialogs (alert / confirm / prompt / beforeunload)               */
    /* ------------------------------------------------------------------ */

    /** Creates the CefJSDialogHandler implementation. */
    public static CefJSDialogHandler jsDialogs(Supplier<java.awt.Window> parent) {
        return new CefJSDialogHandlerAdapter() {
            @Override
            public boolean onJSDialog(CefBrowser browser, String originUrl, JSDialogType type,
                                       String messageText, String defaultPromptText,
                                       CefJSDialogCallback callback, BoolRef suppressMessage) {
                // modal, on the EDT — mirrors native JS dialog semantics
                SwingUtilities.invokeLater(() -> {
                    boolean ok;
                    String input;
                    switch (type) {
                        case JSDIALOGTYPE_ALERT -> {
                            JOptionPane.showMessageDialog(parent.get(), messageText,
                                    appTitle(originUrl), JOptionPane.INFORMATION_MESSAGE);
                            ok = true;
                            input = "";
                        }
                        case JSDIALOGTYPE_CONFIRM -> {
                            ok = JOptionPane.showConfirmDialog(parent.get(), messageText,
                                    appTitle(originUrl),
                                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
                                    == JOptionPane.YES_OPTION;
                            input = "";
                        }
                        case JSDIALOGTYPE_PROMPT -> {
                            Object result = JOptionPane.showInputDialog(parent.get(), messageText,
                                    appTitle(originUrl),
                                    JOptionPane.QUESTION_MESSAGE, null, null, defaultPromptText);
                            ok = result != null; // null = user pressed Cancel
                            input = result == null ? "" : String.valueOf(result);
                        }
                        default -> {
                            ok = true;
                            input = "";
                        }
                    }
                    callback.Continue(ok, input);
                });
                suppressMessage.set(false);
                return true; // handled — never let CEF suppress silently
            }

            @Override
            public boolean onBeforeUnloadDialog(CefBrowser browser, String messageText,
                                                boolean isReload, CefJSDialogCallback callback) {
                SwingUtilities.invokeLater(() -> {
                    int r = JOptionPane.showConfirmDialog(parent.get(),
                            (messageText == null || messageText.isBlank()
                                    ? "Leave this page with unsaved changes?" : messageText),
                            "Chass\u00e9 — unsaved changes",
                            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    callback.Continue(r == JOptionPane.YES_OPTION, "");
                });
                return true;
            }

            @Override
            public void onResetDialogState(CefBrowser browser) {
            }

            @Override
            public void onDialogClosed(CefBrowser browser) {
            }
        };
    }

    private static String appTitle(String originUrl) {
        return originUrl != null && originUrl.length() > 60
                ? "Chass\u00e9 — " + originUrl.substring(0, 60) + "\u2026"
                : "Chass\u00e9 — " + (originUrl == null ? "" : originUrl);
    }

    /* ------------------------------------------------------------------ */
    /* popups → the system browser                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Creates the CefLifeSpanHandler that redirects window.open / target="_blank"
     * to the system browser and cancels the in-app popup.
     */
    public static CefLifeSpanHandler popupsToSystemBrowser(ServerManager servers) {
        return new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser browser, CefFrame frame,
                                          String targetUrl, String targetFrameName) {
                if (targetUrl == null || targetUrl.isBlank()) {
                    return true; // nothing sensible to open — cancel the popup
                }
                if (servers != null) {
                    servers.log("info", "Opening an external page in your browser: " + targetUrl);
                }
                try {
                    if (Desktop.isDesktopSupported()
                            && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(URI.create(targetUrl));
                    } else {
                        // headless fallback — best effort via the OS shell
                        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
                        if (os.contains("win")) {
                            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", targetUrl).start();
                        } else if (os.contains("mac")) {
                            new ProcessBuilder("open", targetUrl).start();
                        } else {
                            new ProcessBuilder("xdg-open", targetUrl).start();
                        }
                    }
                } catch (Throwable t) {
                    if (servers != null) {
                        servers.log("error", "Could not open the external page: " + t.getMessage()
                                + " — URL: " + targetUrl);
                    }
                }
                return true; // popup handled — do NOT create a JCEF popup window
            }
        };
    }

    static {
        // dialogs inherit the app look & feel (best-effort)
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable ignored) {
        }
    }
}
