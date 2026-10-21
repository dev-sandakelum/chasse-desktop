package com.chasse.desktop;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.handler.CefDownloadHandler;
import org.cef.handler.CefDownloadHandlerAdapter;

import java.awt.Desktop;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Native download handling for the embedded webview (the "downloads"
 * capability).
 *
 * <p>JCEF shows NO download UI by default — without this handler the
 * studio's exports (.smdeck / .deck.tsx, and anything else the page offers
 * for download) silently disappear in the desktop app. This class:</p>
 *
 * <ul>
 *   <li>shows a native <b>Save as</b> dialog (JFileChooser on the Swing EDT)
 *       pre-filled with the suggested file name and the user's last download
 *       folder (persisted in {@code ~/.chasse-studio/app.properties})</li>
 *   <li>pushes <b>download events</b> to the page over the bridge
 *       ({@code {"type":"download","stage":...}}) for every stage — started,
 *       saving (path chosen), progress, completed (with the final path) and
 *       failed/cancelled — so the web app can show toasts with an
 *       "Open folder" action instead of staying silent</li>
 *   <li>remembers the folder of the last completed download so
 *       {@link #openLastDownloadFolder()} (the bridge's {@code openDownloadFolder}
 *       action) can reveal it in the system file manager</li>
 * </ul>
 *
 * <p>Cancelling the Save dialog cancels the download cleanly (CEF contract:
 * an empty download path in {@code Continue} cancels).</p>
 */
public final class DownloadBridge implements CefDownloadHandler {

    private final AppConfig config;
    private final Supplier<Window> dialogParent;
    /** event sink = DesktopBridge::pushEvent (set by Main once the bridge exists) */
    private volatile Consumer<String> eventSink;
    /** folder of the most recent completed download (for "Open folder") */
    private volatile Path lastFolder;
    /** downloadId → suggested name (completed/cancelled names for the final event) */
    private final Map<Integer, String> names = new ConcurrentHashMap<>();
    /** downloadId → last progress push (ms) — throttles event spam to ~4/s per download */
    private final Map<Integer, Long> lastProgressAt = new ConcurrentHashMap<>();

    public DownloadBridge(AppConfig config, Supplier<Window> dialogParent) {
        this.config = config;
        this.dialogParent = dialogParent == null ? () -> null : dialogParent;
    }

    /** Sets the push-event sink (DesktopBridge::pushEvent). */
    public void setEventSink(Consumer<String> sink) {
        this.eventSink = sink;
    }

    /* ------------------------------------------------------------------ */
    /* CefDownloadHandler                                                  */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean onBeforeDownload(CefBrowser browser, CefDownloadItem item,
                                    String suggestedName, CefBeforeDownloadCallback callback) {
        int id = item.getId();
        String name = suggestedName == null || suggestedName.isBlank() ? "download" : suggestedName;
        names.put(id, name);
        push(new Evt("started", id).str("fileName", name).o);
        // The dialog runs on the Swing EDT; Continue() may be called later
        // from the EDT — never block the CEF browser thread on a modal dialog.
        SwingUtilities.invokeLater(() -> askPathAndContinue(id, name, callback));
        return true;
    }

    @Override
    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem item,
                                   CefDownloadItemCallback callback) {
        int id = item.getId();
        String name = names.getOrDefault(id, item.getSuggestedFileName());
        if (item.isComplete()) {
            String fullPath = item.getFullPath();
            names.remove(id);
            lastProgressAt.remove(id);
            if (fullPath != null && !fullPath.isBlank()) {
                Path p = Paths.get(fullPath).toAbsolutePath().normalize();
                lastFolder = p.getParent();
            }
            push(new Evt("completed", id).str("fileName", name).str("path", fullPath).o);
            return;
        }
        if (!item.isInProgress()) {
            // interrupted without completing — user cancel or disk error
            names.remove(id);
            lastProgressAt.remove(id);
            push(new Evt("failed", id).str("fileName", name)
                    .str("error", "The download was cancelled or interrupted").o);
            return;
        }
        // progress — throttled: at most one event per ~250 ms per download
        long now = System.currentTimeMillis();
        Long last = lastProgressAt.get(id);
        if (last == null || now - last >= 250) {
            lastProgressAt.put(id, now);
            long total = item.getTotalBytes();
            long received = item.getReceivedBytes();
            int percent = total > 0 ? (int) Math.min(100, received * 100 / total) : 0;
            push(new Evt("progress", id).str("fileName", name)
                    .num("receivedBytes", received).num("totalBytes", total).num("percent", percent).o);
        }
    }

    /* ------------------------------------------------------------------ */
    /* the Save-as dialog                                                  */
    /* ------------------------------------------------------------------ */

    private void askPathAndContinue(int id, String suggestedName, CefBeforeDownloadCallback callback) {
        Path chosen;
        try {
            chosen = pickSavePath(suggestedName);
        } catch (Throwable t) {
            push(new Evt("failed", id).str("fileName", suggestedName)
                    .str("error", "The save dialog could not be opened: " + t.getMessage()).o);
            names.remove(id);
            try {
                callback.Continue("", false); // cancel the download cleanly
            } catch (Throwable ignored) {
            }
            return;
        }
        if (chosen == null) {
            names.remove(id);
            lastProgressAt.remove(id);
            push(new Evt("failed", id).str("fileName", suggestedName)
                    .str("error", "The download was cancelled").o);
            try {
                callback.Continue("", false); // empty path cancels the download (CEF contract)
            } catch (Throwable ignored) {
            }
            return;
        }
        config.saveDownloadsDir(chosen.getParent() == null ? null : chosen.getParent().toString());
        push(new Evt("saving", id).str("fileName", suggestedName).str("path", chosen.toString()).o);
        try {
            callback.Continue(chosen.toString(), false);
        } catch (Throwable t) {
            push(new Evt("failed", id).str("fileName", suggestedName)
                    .str("error", "The download could not be started: " + t.getMessage()).o);
            names.remove(id);
        }
    }

    /** Native Save-as dialog on the EDT (callers must already be on the EDT). */
    private Path pickSavePath(String suggestedName) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save download");
        String last = config.downloadsDir();
        File startDir = null;
        if (last != null && !last.isBlank()) {
            File f = new File(last);
            if (f.isDirectory()) {
                startDir = f;
            }
        }
        if (startDir == null) {
            String home = System.getProperty("user.home");
            File downloads = new File(home, "Downloads");
            startDir = downloads.isDirectory() ? downloads : (home != null ? new File(home) : null);
        }
        if (startDir != null) {
            fc.setCurrentDirectory(startDir);
        }
        fc.setSelectedFile(new File(startDir == null ? "." : startDir.getPath(), sanitizeFileName(suggestedName)));
        fc.setDialogType(JFileChooser.SAVE_DIALOG);
        int dot = suggestedName == null ? -1 : suggestedName.lastIndexOf('.');
        if (dot > 0 && dot < suggestedName.length() - 1) {
            String ext = suggestedName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            if (ext.matches("[a-z0-9]{1,12}")) {
                fc.setFileFilter(new FileNameExtensionFilter("*." + ext, ext));
            }
        }
        Window parent = dialogParent.get();
        int r = parent != null ? fc.showSaveDialog(parent) : fc.showSaveDialog(null);
        if (r != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File f = fc.getSelectedFile();
        if (f == null) {
            return null;
        }
        String name = f.getName();
        if (name.isBlank() || name.equals(".")) {
            return null;
        }
        // keep the suggested extension when the user typed a name without one
        if (dot > 0 && !name.contains(".")) {
            f = new File(f.getParentFile(), name + suggestedName.substring(dot));
        }
        return f.toPath().toAbsolutePath().normalize();
    }

    /** Strips path separators and reserved characters out of a download file name. */
    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "download";
        }
        String cleaned = raw.replace('\\', '_').replace('/', '_')
                .replace(':', '_').replace('*', '_').replace('?', '_')
                .replace('"', '_').replace('<', '_').replace('>', '_').replace('|', '_')
                .trim();
        return cleaned.isBlank() ? "download" : cleaned;
    }

    /* ------------------------------------------------------------------ */
    /* reveal                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Opens the folder of the last completed download in the system file
     * manager. Never touches arbitrary caller-chosen paths: the folder comes
     * from this session's own completed downloads only.
     */
    public boolean openLastDownloadFolder() {
        Path folder = lastFolder;
        if (folder != null && Files.isDirectory(folder)) {
            return browse(folder.toFile());
        }
        String last = config.downloadsDir();
        if (last != null && !last.isBlank()) {
            File f = new File(last);
            if (f.isDirectory()) {
                return browse(f);
            }
        }
        return false;
    }

    private static boolean browse(File dir) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                Desktop.getDesktop().browseFileDirectory(dir);
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("win")) {
                // opening the folder in Explorer is enough feedback for "Open folder"
                new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* event push                                                          */
    /* ------------------------------------------------------------------ */

    /** Small fluent builder so download events read as one chained expression. */
    private static final class Evt {
        final com.google.gson.JsonObject o = new com.google.gson.JsonObject();

        Evt(String stage, int id) {
            o.addProperty("type", "download");
            o.addProperty("stage", stage);
            o.addProperty("id", id);
        }

        Evt str(String key, String value) {
            o.addProperty(key, value == null ? "" : value);
            return this;
        }

        Evt num(String key, long value) {
            o.addProperty(key, value);
            return this;
        }
    }

    private void push(com.google.gson.JsonObject o) {
        Consumer<String> sink = eventSink;
        if (sink != null) {
            sink.accept(o.toString());
        }
    }
}
