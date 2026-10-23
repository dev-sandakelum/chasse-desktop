package com.chasse.desktop;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefFileDialogCallback;
import org.cef.handler.CefDialogHandler;
import org.cef.handler.CefDialogHandler.FileDialogMode;

import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.function.Supplier;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Native file chooser for the embedded webview (the "file chooser" half of
 * the native-UX trio: downloads → DownloadBridge, JS dialogs → WindowDialogs,
 * &lt;input type="file"&gt; → here).
 *
 * <p>The DEFAULT file-dialog handling is unreliable across JCEF builds (some
 * silently cancel the chooser, leaving "Import…"/"Load .smdeck…" dead in the
 * app). Taking the dialog over explicitly guarantees a real chooser:</p>
 *
 * <ul>
 *   <li>open / open-multiple → JFileChooser with multi-selection</li>
 *   <li>save → save dialog with the suggested name and extension filter</li>
 *   <li>the accept extensions from the page become the chooser's filter</li>
 *   <li>FILE_DIALOG_OPEN_FOLDER → directories-only chooser</li>
 * </ul>
 *
 * <p>The dialog runs on the Swing EDT (never blocking the CEF UI thread) and
 * answers the callback from there — Continue/Cancel may be called from any
 * thread.</p>
 */
public final class FileDialogs {
    private FileDialogs() {
    }

    /** Creates the CefDialogHandler implementation. */
    public static CefDialogHandler fileDialogs(Supplier<Window> parent) {
        return new CefDialogHandler() {
            @Override
            public boolean onFileDialog(CefBrowser browser, FileDialogMode mode, String title,
                                        String defaultFileName,
                                        Vector<String> acceptTypes,
                                        Vector<String> callbackTexts,
                                        Vector<String> callbackPaths,
                                        CefFileDialogCallback callback) {
                final boolean multi = mode == FileDialogMode.FILE_DIALOG_OPEN_MULTIPLE;
                final boolean folder = mode == FileDialogMode.FILE_DIALOG_OPEN_FOLDER;
                final boolean save = mode == FileDialogMode.FILE_DIALOG_SAVE;
                SwingUtilities.invokeLater(() -> {
                    try {
                        JFileChooser fc = new JFileChooser();
                        fc.setDialogTitle(title == null || title.isBlank()
                                ? (save ? "Save file" : folder ? "Choose folder" : "Choose file") : title);
                        if (multi) fc.setMultiSelectionEnabled(true);
                        if (folder) fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                        // the page's accept filter — one readable label per extension
                        List<String> exts = new ArrayList<>();
                        if (acceptTypes != null) {
                            for (String t : acceptTypes) {
                                String e = t == null ? "" : t.replaceFirst("^\\.", "").toLowerCase(java.util.Locale.ROOT);
                                if (e.matches("[a-z0-9]{1,12}") && !exts.contains(e)) exts.add(e);
                            }
                        }
                        if (!folder && !exts.isEmpty()) {
                            fc.setFileFilter(new FileNameExtensionFilter(
                                    String.join(", ", exts), exts.toArray(new String[0])));
                        }
                        File start = new File(System.getProperty("user.home"), "Downloads");
                        if (!start.isDirectory()) start = new File(System.getProperty("user.home"));
                        fc.setCurrentDirectory(start);
                        if (defaultFileName != null && !defaultFileName.isBlank() && !folder) {
                            fc.setSelectedFile(new File(start, defaultFileName));
                        }
                        Window owner = parent.get();
                        int r = save
                                ? fc.showSaveDialog(owner)
                                : fc.showOpenDialog(owner);
                        if (r != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
                            callback.Cancel();
                            return;
                        }
                        Vector<String> paths = new Vector<>();
                        if (multi && fc.getSelectedFiles() != null && fc.getSelectedFiles().length > 0) {
                            for (File f : fc.getSelectedFiles()) paths.add(f.getAbsolutePath());
                        } else {
                            paths.add(fc.getSelectedFile().getAbsolutePath());
                        }
                        if (paths.isEmpty()) {
                            callback.Cancel();
                            return;
                        }
                        callback.Continue(paths);
                    } catch (Throwable t) {
                        System.err.println("[file-dialog] failed: " + t);
                        try { callback.Cancel(); } catch (Throwable ignored) {}
                    }
                });
                return true; // handled — we own the dialog
            }
        };
    }
}
