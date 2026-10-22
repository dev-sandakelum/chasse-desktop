package com.chasse.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Owns the decks folder and every file operation on it (Desktop Bridge
 * Protocol v2 — the {@code fs} capability).
 *
 * <p>The user picks ONE folder (native dialog, {@link #chooseRoot()}); the
 * choice is persisted in {@code ~/.chasse-studio/config.json} and reused on
 * every start, so nothing "expires" the way browser File System Access
 * permissions do. Every deck is a sub-folder containing {@code deck.json}
 * (the same folder-is-project layout the web app uses), which also adopts
 * decks created by the browser version when the user picks the folder that
 * contains them.</p>
 *
 * <p>Security: every path is confined to the chosen root — lexical segment
 * validation (no {@code ..}, absolute paths, separators, Windows reserved
 * names) plus a canonical {@code toRealPath()} containment check that follows
 * symlinks/junctions and rejects anything resolving outside. All writes are
 * atomic (temp file in the same folder → fsync → rename over the target) and
 * only ever touch the file that changed. Deletes return success only after
 * the files are actually gone, with the real OS reason on failure.</p>
 */
public final class FsService {

    /* ------------------------------------------------------------------ */
    /* Error codes (contract)                                              */
    /* ------------------------------------------------------------------ */

    public static final String CODE_FOLDER_NOT_SET = "FOLDER_NOT_SET";
    public static final String CODE_FOLDER_MISSING = "FOLDER_MISSING";
    public static final String CODE_NO_OS_PERMISSION = "NO_OS_PERMISSION";
    public static final String CODE_NOT_FOUND = "NOT_FOUND";
    public static final String CODE_TOO_LARGE = "TOO_LARGE";
    public static final String CODE_IO_ERROR = "IO_ERROR";
    // protocol-level extensions (same {code, message} shape)
    public static final String CODE_ALREADY_EXISTS = "ALREADY_EXISTS";
    public static final String CODE_BAD_REQUEST = "BAD_REQUEST";
    public static final String CODE_CANCELLED = "CANCELLED";
    public static final String CODE_FORBIDDEN = "FORBIDDEN";
    public static final String CODE_UNKNOWN_CMD = "UNKNOWN_CMD";

    /** Contract limits. Text commands carry whole files as JSON strings, so
     *  they get a small limit; uploads stream through the loopback server and
     *  get a generous one. */
    public static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;        // 2 MiB
    public static final long MAX_UPLOAD_BYTES = 256L * 1024 * 1024;    // 256 MiB
    public static final int MAX_CREATE_FILES = 500;

    private static final Pattern DECK_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final int MAX_PATH = 240;
    private static final long SCAN_TTL_MS = 2000;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** Classic reserved device names on Windows (case-insensitive, with or
     *  without an extension). A segment like {@code CON} or {@code com3.txt}
     *  can't be addressed reliably, so it is rejected outright. */
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
            "CONIN$", "CONOUT$");

    /* ------------------------------------------------------------------ */
    /* State                                                               */
    /* ------------------------------------------------------------------ */

    /** Thrown with a contract error code; the bridge turns it into {code, message}. */
    public static final class FsException extends Exception {
        private static final long serialVersionUID = 1L;

        public final String code;

        public FsException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** One scanned deck folder: {id, title, folder, slides, modified}. */
    private static final class DeckEntry {
        final String id;
        final String title;
        final String folder;
        final int slides;
        final String modified;
        final long modifiedMs;

        DeckEntry(String id, String title, String folder, int slides, String modified, long modifiedMs) {
            this.id = id;
            this.title = title;
            this.folder = folder;
            this.slides = slides;
            this.modified = modified;
            this.modifiedMs = modifiedMs;
        }
    }

    private final Path configFile;
    private final Path catalogFile;
    private final Object lock = new Object();          // root + persisted config
    private final Object scanLock = new Object();      // deck list cache
    private Path root = null;
    private String confirmedRoot = null;
    private List<DeckEntry> cache = new ArrayList<>();
    private long lastScan = 0;
    /** Catalog entries are the only permission grant for folders outside root. */
    private final Map<String, Path> catalog = new LinkedHashMap<>();
    /** Canonical paths explicitly removed from the library are never re-adopted. */
    private final Set<String> removedFolders = new HashSet<>();
    private final Map<String, Path> pendingCandidates = new HashMap<>();
    private long pendingCandidatesAt = 0;
    private final AtomicBoolean chooserOpen = new AtomicBoolean(false);

    /** Lazily dereferenced parent window for the native dialogs (set by Main). */
    private volatile Supplier<Window> dialogParent = () -> null;

    public FsService() {
        this(AppConfig.USER_DIR.resolve("config.json"));
    }

    /** Test seam: an explicit config file location (temp dirs in unit tests). */
    FsService(Path configFile) {
        this.configFile = configFile;
        this.catalogFile = configFile.resolveSibling("catalog.json");
        loadConfig();
        loadCatalog();
    }

    /** Parent window for dialogs; dereferenced only when a dialog opens. */
    public void setDialogParent(Supplier<Window> parent) {
        this.dialogParent = parent == null ? () -> null : parent;
    }

    /* ------------------------------------------------------------------ */
    /* persisted config (~/.chasse-studio/config.json)                     */
    /* ------------------------------------------------------------------ */

    private void loadConfig() {
        try {
            if (!Files.isRegularFile(configFile)) {
                return;
            }
            JsonObject o = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement r = o.get("decksRoot");
            if (r != null && r.isJsonPrimitive() && !r.getAsString().isBlank()) {
                root = Path.of(r.getAsString().trim());
            }
            JsonElement c = o.get("confirmedRoot");
            if (c != null && c.isJsonPrimitive()) {
                confirmedRoot = c.getAsString();
            }
        } catch (Exception e) {
            System.err.println("[fs] could not read " + configFile + ": " + e.getMessage());
        }
    }

    private void saveConfig() {
        JsonObject o = new JsonObject();
        synchronized (lock) {
            if (root != null) {
                o.addProperty("decksRoot", root.toString());
            }
            if (confirmedRoot != null) {
                o.addProperty("confirmedRoot", confirmedRoot);
            }
        }
        try {
            Files.createDirectories(configFile.getParent());
            atomicWriteBytes(configFile, o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[fs] could not save " + configFile + ": " + e.getMessage());
        }
    }

    private void loadCatalog() {
        try {
            if (!Files.isRegularFile(catalogFile)) return;
            JsonObject source = JsonParser.parseString(Files.readString(catalogFile, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement decks = source.get("decks");
            if (decks != null && decks.isJsonArray()) {
                for (JsonElement item : decks.getAsJsonArray()) {
                    if (!item.isJsonObject()) continue;
                    JsonObject entry = item.getAsJsonObject();
                    String id = entry.has("id") ? entry.get("id").getAsString() : null;
                    String folder = entry.has("folder") ? entry.get("folder").getAsString() : null;
                    if (id != null && folder != null && DECK_ID.matcher(id).matches()) catalog.put(id, Path.of(folder));
                }
            }
            JsonElement removed = source.get("removedFolders");
            if (removed != null && removed.isJsonArray()) {
                for (JsonElement item : removed.getAsJsonArray()) if (item.isJsonPrimitive()) removedFolders.add(item.getAsString());
            }
        } catch (Exception e) {
            System.err.println("[fs] could not read " + catalogFile + ": " + e.getMessage());
        }
    }

    private void saveCatalog() throws FsException {
        JsonObject out = new JsonObject();
        JsonArray decks = new JsonArray();
        synchronized (lock) {
            for (Map.Entry<String, Path> entry : catalog.entrySet()) {
                JsonObject deck = new JsonObject();
                deck.addProperty("id", entry.getKey());
                deck.addProperty("folder", entry.getValue().toString());
                decks.add(deck);
            }
            out.add("decks", decks);
            JsonArray removed = new JsonArray();
            for (String folder : removedFolders) removed.add(folder);
            out.add("removedFolders", removed);
        }
        try {
            Files.createDirectories(catalogFile.getParent());
            atomicWriteBytes(catalogFile, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw ioOf(e, "write library catalog");
        }
    }

    /* ------------------------------------------------------------------ */
    /* fs.getRoot / fs.chooseRoot                                          */
    /* ------------------------------------------------------------------ */

    /** {configured, path, valid} — never throws; it IS the health report. */
    public JsonObject getRoot() {
        Path r;
        synchronized (lock) {
            r = root;
        }
        JsonObject o = new JsonObject();
        o.addProperty("configured", r != null);
        o.addProperty("path", r == null ? "" : r.toString());
        o.addProperty("valid", r != null && rootUsable(r));
        return o;
    }

    private static boolean rootUsable(Path r) {
        try {
            return Files.isDirectory(r) && Files.isReadable(r) && Files.isWritable(r);
        } catch (Exception e) {
            return false;
        }
    }

    public JsonObject chooseRoot() throws FsException {
        return chooseRoot(null, null, null);
    }

    /**
     * Native folder chooser (JFileChooser on the Swing EDT — never on a JCEF
     * or pool thread). Cancelling returns the CURRENT persisted state cleanly
     * (no error, nothing changed). The first time a NEW folder is picked a
     * short confirmation asks the user to allow read/write access to it.
     *
     * <p>Web-supplied copy (Desktop Bridge Protocol v2): an optional dialog
     * {@code title}, a {@code startPath} the chooser opens at when it still
     * exists, and a one-line {@code hint} shown in the grant confirmation.</p>
     */
    public JsonObject chooseRoot(String title, String startPath, String hint) throws FsException {
        Path picked = pickFolderOnEdt(title, startPath);
        if (picked == null) {
            return getRoot(); // user cancelled — clean, unchanged
        }
        Path canonical = picked.toAbsolutePath().normalize();
        String canonicalStr = canonical.toString();
        synchronized (lock) {
            boolean known = canonicalStr.equals(confirmedRoot);
            if (!known && !confirmOnEdt(canonical, hint)) {
                return getRoot(); // declined — unchanged
            }
            confirmedRoot = canonicalStr;
            root = canonical;
        }
        saveConfig();
        invalidateCache();
        System.out.println("[fs] decks folder set to " + canonical);
        return getRoot();
    }

    private Path pickFolderOnEdt(String title, String startPath) throws FsException {
        if (SwingUtilities.isEventDispatchThread()) {
            return pickFolder(dialogParent.get(), title, startPath);
        }
        final Path[] out = new Path[1];
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = pickFolder(dialogParent.get(), title, startPath));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FsException(CODE_CANCELLED, "folder selection was interrupted");
        } catch (Exception e) {
            throw new FsException(CODE_IO_ERROR, "folder selection failed: " + e.getMessage());
        }
        return out[0];
    }

    private Path pickFolder(Window parent, String title, String startPath) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(title == null || title.isBlank()
                ? "Choose your Chassé decks folder" : title);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setAcceptAllFileFilterUsed(false);
        Path current;
        synchronized (lock) {
            current = root;
        }
        // the web app's suggested start folder wins when it exists; otherwise
        // the last chosen root; otherwise the system default location
        if (startPath != null && !startPath.isBlank()) {
            try {
                Path sp = Path.of(startPath);
                if (Files.isDirectory(sp)) {
                    fc.setCurrentDirectory(sp.toFile());
                    current = null;
                }
            } catch (Exception ignored) {
                /* invalid start path — fall through to the persisted root */
            }
        }
        if (current != null && Files.isDirectory(current)) {
            fc.setCurrentDirectory(current.toFile());
        }
        int r = fc.showOpenDialog(parent);
        if (r != JFileChooser.APPROVE_OPTION) {
            return null; // cancelled — handled by the caller
        }
        java.io.File f = fc.getSelectedFile();
        return f == null ? null : f.toPath();
    }

    private boolean confirmOnEdt(Path folder, String hint) {
        final boolean[] out = new boolean[1];
        String message = (hint == null || hint.isBlank()
                ? ""
                : hint.trim() + "\n\n")
                + "Allow Chasse Studio to read and write decks in this folder?\n\n" + folder;
        Runnable ask = () -> out[0] = JOptionPane.showConfirmDialog(dialogParent.get(),
                message,
                "Allow folder access", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE)
                == JOptionPane.YES_OPTION;
        if (SwingUtilities.isEventDispatchThread()) {
            ask.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(ask);
            } catch (Exception e) {
                return false; // can't ask → don't grant
            }
        }
        return out[0];
    }

    /* ------------------------------------------------------------------ */
    /* root / deck resolution (the confinement core)                       */
    /* ------------------------------------------------------------------ */

    /** Re-checks the root before every operation (contract C). */
    private Path requireRootValid() throws FsException {
        Path r;
        synchronized (lock) {
            r = root;
        }
        if (r == null) {
            throw new FsException(CODE_FOLDER_NOT_SET,
                    "No decks folder has been chosen yet — call fs.chooseRoot first");
        }
        if (!Files.isDirectory(r)) {
            throw new FsException(CODE_FOLDER_MISSING,
                    "The decks folder no longer exists: " + r + " — choose the folder again");
        }
        if (!Files.isReadable(r)) {
            throw new FsException(CODE_NO_OS_PERMISSION,
                    "The decks folder is not readable: " + r);
        }
        if (!Files.isWritable(r)) {
            throw new FsException(CODE_NO_OS_PERMISSION,
                    "The decks folder is not writable: " + r);
        }
        return r;
    }

    /** The deck's folder — must exist and contain deck.json. */
    private Path requireDeckDir(String deck) throws FsException {
        if (deck == null || !DECK_ID.matcher(deck).matches()) {
            throw new FsException(CODE_BAD_REQUEST, "Invalid deck id");
        }
        Path dir;
        synchronized (lock) {
            dir = catalog.get(deck);
        }
        if (dir == null) {
            Path r = requireRootValid();
            dir = r.resolve(deck);
            String canonical = dir.toAbsolutePath().normalize().toString();
            synchronized (lock) {
                if (removedFolders.contains(canonical)) {
                    throw new FsException(CODE_NOT_FOUND, "Deck was removed from the library: " + deck);
                }
            }
        }
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("deck.json"))) {
            throw new FsException(CODE_NOT_FOUND, "Deck not found: " + deck);
        }
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            throw ioOf(e, "access deck " + deck);
        }
    }

    /** Same-folder validation used by the HTTP read path (deck must exist). */
    Path resolveHttpReadTarget(String deck, String rel) throws FsException {
        Path dir = requireDeckDir(deck);
        return resolveConfined(dir, rel);
    }

    /** Resolve a write target inside an existing deck (used by HTTP PUT). */
    Path resolveHttpWriteTarget(String deck, String rel) throws FsException {
        Path dir = requireDeckDir(deck);
        Path target = resolveConfined(dir, rel);
        if (Files.isSymbolicLink(target)) {
            throw bad("refusing to write through a symbolic link: " + rel);
        }
        return target;
    }

    private static FsException bad(String message) {
        return new FsException(CODE_BAD_REQUEST, message);
    }

    /** Lexical segment validation — rejects traversal, separators, absolute
     *  paths, over-long paths and (on Windows) reserved device names. */
    static void checkRelPath(String rel) throws FsException {
        if (rel == null || rel.isEmpty()) {
            throw bad("path must not be empty");
        }
        if (rel.length() > MAX_PATH) {
            throw bad("path is too long (max " + MAX_PATH + " chars)");
        }
        if (rel.indexOf('\0') >= 0 || rel.indexOf('\\') >= 0) {
            throw bad("path contains illegal characters");
        }
        if (rel.charAt(0) == '/') {
            throw bad("path must be relative to the deck folder");
        }
        for (String seg : rel.split("/")) {
            checkSegment(seg, rel);
        }
    }

    private static void checkSegment(String seg, String full) throws FsException {
        if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
            throw bad("illegal path segment in: " + full);
        }
        if (seg.length() > 255) {
            throw bad("path segment too long in: " + full);
        }
        if (seg.indexOf('\0') >= 0) {
            throw bad("illegal character in: " + full);
        }
        if (IS_WINDOWS) {
            String upper = seg.toUpperCase(Locale.ROOT);
            String base = upper;
            int dot = upper.indexOf('.');
            if (dot > 0) {
                base = upper.substring(0, dot);
            }
            if (WINDOWS_RESERVED.contains(base)) {
                throw bad("reserved Windows name: " + seg);
            }
            if (seg.endsWith(".") || seg.endsWith(" ")) {
                throw bad("name must not end with '.' or space: " + seg);
            }
            if (seg.length() >= 2 && seg.charAt(1) == ':') {
                throw bad("drive reference is not allowed: " + seg);
            }
        }
    }

    /**
     * Resolves {@code rel} inside {@code base} and confines the result:
     * lexical check first, then a canonical {@code toRealPath()} containment
     * check that follows symlinks/junctions — for files that do not exist yet
     * the nearest EXISTING ancestor is checked, so a symlink pointing outside
     * the root can never be written through.
     */
    private Path resolveConfined(Path base, String rel) throws FsException {
        checkRelPath(rel);
        Path target = base.resolve(rel).normalize();
        Path realBase = realPathOf(base);
        if (!target.startsWith(base)) {
            throw bad("path escapes the deck folder: " + rel);
        }
        Path existing = target;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw bad("path escapes the deck folder: " + rel);
        }
        if (!realPathOf(existing).startsWith(realBase)) {
            throw bad("path resolves outside the decks folder: " + rel);
        }
        return target;
    }

    private static Path realPathOf(Path p) throws FsException {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            throw new FsException(CODE_NOT_FOUND, "path not accessible: " + p.getFileName());
        }
    }

    /* ------------------------------------------------------------------ */
    /* fs.listDecks (scan + cache)                                         */
    /* ------------------------------------------------------------------ */

    /** Forces the next listDecks to re-scan (called after every mutation). */
    void invalidateCache() {
        synchronized (scanLock) {
            lastScan = 0;
        }
    }

    /**
     * Every folder under the root that contains a {@code deck.json}. Cached
     * and re-scanned at most every {@link #SCAN_TTL_MS} (a scan reads only
     * deck.json of each deck — cheap on a local disk); any mutation through
     * this service invalidates the cache immediately.
     */
    public JsonArray listDecks() throws FsException {
        Path r = requireRootValid();
        synchronized (scanLock) {
            if (lastScan == 0 || System.currentTimeMillis() - lastScan > SCAN_TTL_MS) {
                rescanLocked(r);
            }
            JsonArray arr = new JsonArray();
            for (DeckEntry e : cache) {
                JsonObject o = new JsonObject();
                o.addProperty("id", e.id);
                o.addProperty("title", e.title);
                o.addProperty("folder", e.folder);
                o.addProperty("slides", e.slides);
                o.addProperty("modified", e.modified);
                arr.add(o);
            }
            return arr;
        }
    }

    private void rescanLocked(Path r) throws FsException {
        Map<String, DeckEntry> out = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(r)) {
            for (Path p : ds) {
                // symlinked folders are skipped: a deck folder must be a REAL
                // directory inside the root (containment is verified per-file
                // anyway, but a symlinked deck would just error on every use)
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (!DECK_ID.matcher(name).matches()) {
                    continue;
                }
                Path deckJson = p.resolve("deck.json");
                if (!Files.isRegularFile(deckJson)) {
                    continue;
                }
                String canonical = p.toRealPath().toString();
                String id = deckIdForFolder(p, name);
                synchronized (lock) {
                    if (removedFolders.contains(canonical)) continue;
                    Path known = catalog.get(id);
                    if (known != null && !known.toAbsolutePath().normalize().equals(p.toRealPath())) continue;
                    catalog.putIfAbsent(id, p.toRealPath());
                }
                out.put(id, readEntry(id, p, deckJson));
            }
        } catch (IOException e) {
            throw new FsException(CODE_IO_ERROR, "could not scan the decks folder: " + e.getMessage());
        }
        synchronized (lock) {
            for (Map.Entry<String, Path> entry : catalog.entrySet()) {
                Path folder = entry.getValue();
                try {
                    if (Files.isDirectory(folder) && Files.isRegularFile(folder.resolve("deck.json"))) {
                        out.put(entry.getKey(), readEntry(entry.getKey(), folder, folder.resolve("deck.json")));
                    }
                } catch (Exception ignored) {
                    // Keep a missing catalog entry out of this response; file operations report its real error.
                }
            }
        }
        List<DeckEntry> sorted = new ArrayList<>(out.values());
        sorted.sort(Comparator.comparingLong((DeckEntry e) -> e.modifiedMs).reversed());
        cache = sorted;
        lastScan = System.currentTimeMillis();
        saveCatalog();
    }

    private DeckEntry readEntry(String id, Path dir, Path deckJson) {
        String title = id;
        int slides = 0;
        String modified = null;
        long modifiedMs = 0;
        try {
            modifiedMs = Files.getLastModifiedTime(deckJson).toMillis();
            modified = Instant.ofEpochMilli(modifiedMs).toString();
            JsonObject o = JsonParser.parseString(Files.readString(deckJson, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement t = o.get("title");
            if (t != null && t.isJsonPrimitive() && !t.getAsString().isBlank()) {
                title = t.getAsString();
            }
            JsonElement s = o.get("slides");
            if (s != null && s.isJsonArray()) {
                slides = s.getAsJsonArray().size();
            }
            JsonElement m = o.get("modified");
            if (m != null && m.isJsonPrimitive()) {
                try {
                    Instant inst = Instant.parse(m.getAsString());
                    modified = m.getAsString();
                    modifiedMs = inst.toEpochMilli();
                } catch (DateTimeParseException ignored) {
                    // keep the file mtime
                }
            }
        } catch (Exception ignored) {
            // unreadable/unparseable deck.json — still list the folder (title
            // falls back to the folder name); reads/writes surface real errors
        }
        return new DeckEntry(id, title, dir.toString(), slides, modified, modifiedMs);
    }

    /** Uses deck.json's id for linked folders; the folder name is only the
     * legacy/default-root fallback. This permits folders with spaces and
     * non-English names without widening the bridge's deck-id protocol. */
    private String deckIdForFolder(Path folder, String fallback) throws FsException {
        try {
            JsonObject deck = JsonParser.parseString(Files.readString(folder.resolve("deck.json"), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement id = deck.get("id");
            if (id != null && id.isJsonPrimitive() && DECK_ID.matcher(id.getAsString()).matches()) return id.getAsString();
        } catch (Exception ignored) {
            // readEntry retains the established tolerant handling for malformed metadata.
        }
        if (DECK_ID.matcher(fallback).matches()) return fallback;
        throw new FsException(CODE_BAD_REQUEST, "deck.json must contain a valid deck id");
    }

    /* ------------------------------------------------------------------ */
    /* fs.readText / fs.writeText                                          */
    /* ------------------------------------------------------------------ */

    public JsonObject readText(String deck, String path) throws FsException {
        Path file = resolveConfined(requireDeckDir(deck), path);
        if (!Files.isRegularFile(file)) {
            throw new FsException(CODE_NOT_FOUND, "File not found: " + path);
        }
        try {
            long size = Files.size(file);
            if (size > MAX_TEXT_BYTES) {
                throw new FsException(CODE_TOO_LARGE,
                        "File is " + size + " bytes — the text limit is " + MAX_TEXT_BYTES + " bytes: " + path);
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject o = new JsonObject();
            o.addProperty("text", text);
            return o;
        } catch (IOException e) {
            throw ioOf(e, "read " + path);
        }
    }

    public JsonObject writeText(String deck, String path, String text) throws FsException {
        if (text == null) {
            throw bad("text is required");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            throw new FsException(CODE_TOO_LARGE,
                    "File is " + bytes.length + " bytes — the text limit is " + MAX_TEXT_BYTES + " bytes: " + path);
        }
        Path dir = requireDeckDir(deck);
        Path file = resolveConfined(dir, path);
        if (Files.isSymbolicLink(file)) {
            throw bad("refusing to write through a symbolic link: " + path);
        }
        try {
            backupBeforeWrite(dir, file, path);
            atomicWriteBytes(file, bytes);
        } catch (IOException e) {
            throw ioOf(e, "write " + path);
        }
        invalidateCache();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private void backupBeforeWrite(Path deckDir, Path file, String rel) throws IOException {
        if (!Files.isRegularFile(file)) return;
        Path history = deckDir.resolve(".history");
        Path snapshot = history.resolve(System.currentTimeMillis() + "-" + java.util.UUID.randomUUID());
        Path copy = resolveConfined(snapshot, rel);
        Files.createDirectories(copy.getParent());
        copyFileAtomic(file, copy);
        try (var entries = Files.list(history)) {
            for (Path old : entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(FsService::lastModified).reversed())
                    .skip(10).toList()) {
                deleteTreeBestEffort(old);
            }
        }
    }

    private static long lastModified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); } catch (IOException ignored) { return 0; }
    }

    /* ------------------------------------------------------------------ */
    /* fs.createDeck / fs.deleteDeck / fs.duplicateDeck                   */
    /* ------------------------------------------------------------------ */

    /**
     * Creates {@code root/<deck>} from a file map ({@code path → text}). All
     * files are validated BEFORE anything touches the disk; {@code deck.json}
     * is written LAST (the web app's folder-is-project convention: an
     * unfinished deck never looks complete).
     */
    public JsonObject createDeck(String deck, JsonObject files) throws FsException {
        Path r = requireRootValid();
        if (deck == null || !DECK_ID.matcher(deck).matches()) {
            throw bad("Invalid deck id");
        }
        if (files == null || files.size() == 0) {
            throw bad("files map is required");
        }
        if (files.size() > MAX_CREATE_FILES) {
            throw new FsException(CODE_TOO_LARGE, "too many files (" + files.size() + " > " + MAX_CREATE_FILES + ")");
        }
        Map<String, byte[]> toWrite = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : files.entrySet()) {
            String p = e.getKey();
            checkRelPath(p);
            JsonElement v = e.getValue();
            if (v == null || !v.isJsonPrimitive()) {
                throw bad("file content must be a string: " + p);
            }
            byte[] bytes = v.getAsString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_TEXT_BYTES) {
                throw new FsException(CODE_TOO_LARGE,
                        "File is " + bytes.length + " bytes — the text limit is " + MAX_TEXT_BYTES + " bytes: " + p);
            }
            toWrite.put(p, bytes);
        }
        if (!toWrite.containsKey("deck.json")) {
            throw bad("files must include deck.json");
        }
        Path dir = r.resolve(deck);
        if (Files.exists(dir)) {
            throw new FsException(CODE_ALREADY_EXISTS, "a deck folder named \"" + deck + "\" already exists");
        }
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, byte[]> e : toWrite.entrySet()) {
                if ("deck.json".equals(e.getKey())) {
                    continue; // index last
                }
                atomicWriteBytes(resolveConfined(dir, e.getKey()), e.getValue());
            }
            atomicWriteBytes(resolveConfined(dir, "deck.json"), toWrite.get("deck.json"));
        } catch (IOException | FsException e) {
            deleteTreeBestEffort(dir);
            if (e instanceof FsException fe) {
                throw fe;
            }
            throw ioOf((IOException) e, "create deck " + deck);
        }
        invalidateCache();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("id", deck);
        return o;
    }

    /** Removes the whole deck folder; ok only once it is actually gone. */
    public JsonObject deleteDeck(String deck) throws FsException {
        Path dir = requireDeckDir(deck);
        deleteTreeVerified(dir);
        synchronized (lock) {
            catalog.remove(deck);
        }
        saveCatalog();
        invalidateCache();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    /**
     * Opens a native folder chooser and adds only the chosen deck folder to the
     * catalog. The page never submits a path: nested results must be confirmed
     * with short-lived tokens created by this method.
     */
    public JsonObject chooseDeckFolder(String title, String startPath) throws FsException {
        return chooseDeckFolder(null, title, startPath);
    }

    /** Re-links an existing catalog id when {@code relinkId} is non-null. */
    public JsonObject chooseDeckFolder(String relinkId, String title, String startPath) throws FsException {
        if (!chooserOpen.compareAndSet(false, true)) {
            throw new FsException("BUSY", "A folder chooser is already open");
        }
        try {
            Path chosen = pickFolderOnEdt(title, startPath);
            if (chosen == null) {
                JsonObject cancelled = new JsonObject();
                cancelled.addProperty("cancelled", true);
                return cancelled;
            }
            Path folder;
            try {
                folder = chosen.toRealPath();
            } catch (IOException e) {
                throw new FsException("NOT_READABLE", "Could not read the selected folder: " + e.getMessage());
            }
            if (!Files.isReadable(folder)) throw new FsException("NOT_READABLE", "The selected folder is not readable");
            if (Files.isRegularFile(folder.resolve("deck.json"))) {
                return addChosenDeck(folder, relinkId);
            }
            List<Path> candidates = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(folder)) {
                for (Path child : children) {
                    if (Files.isDirectory(child) && Files.isRegularFile(child.resolve("deck.json"))) candidates.add(child.toRealPath());
                }
            } catch (IOException e) {
                throw new FsException("NOT_READABLE", "Could not inspect the selected folder: " + e.getMessage());
            }
            if (candidates.isEmpty()) throw new FsException("NO_DECK_JSON", "This folder doesn't contain a deck.json file");
            JsonArray listed = new JsonArray();
            synchronized (lock) {
                pendingCandidates.clear();
                pendingCandidatesAt = System.currentTimeMillis();
                for (Path candidate : candidates) {
                    String token = UUID.randomUUID().toString();
                    pendingCandidates.put(token, candidate);
                    DeckEntry entry = readEntry(candidate.getFileName().toString(), candidate, candidate.resolve("deck.json"));
                    JsonObject item = new JsonObject();
                    item.addProperty("token", token);
                    item.addProperty("title", entry.title);
                    item.addProperty("folder", candidate.toString());
                    item.addProperty("slides", entry.slides);
                    listed.add(item);
                }
            }
            JsonObject result = new JsonObject();
            result.addProperty("needsConfirm", true);
            result.add("candidates", listed);
            return result;
        } finally {
            chooserOpen.set(false);
        }
    }

    public JsonObject confirmAddDecks(JsonArray tokens) throws FsException {
        if (tokens == null || tokens.size() == 0) throw bad("tokens are required");
        List<Path> selected = new ArrayList<>();
        synchronized (lock) {
            if (System.currentTimeMillis() - pendingCandidatesAt > 5 * 60_000L) {
                pendingCandidates.clear();
                throw new FsException(CODE_BAD_REQUEST, "The folder scan expired; choose the folder again");
            }
            for (JsonElement token : tokens) {
                if (!token.isJsonPrimitive()) throw bad("invalid deck token");
                Path path = pendingCandidates.get(token.getAsString());
                if (path == null) throw bad("unknown deck token");
                selected.add(path);
            }
            pendingCandidates.clear();
        }
        JsonArray added = new JsonArray();
        for (Path path : selected) {
            JsonObject result = addChosenDeck(path, null);
            JsonElement entries = result.get("added");
            if (entries != null && entries.isJsonArray()) for (JsonElement entry : entries.getAsJsonArray()) added.add(entry);
        }
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("added", added);
        return result;
    }

    public JsonObject removeFromLibrary(String deck) throws FsException {
        Path folder;
        synchronized (lock) {
            folder = catalog.get(deck);
            if (folder == null) {
                Path r = requireRootValid();
                folder = r.resolve(deck);
            }
            catalog.remove(deck);
            removedFolders.add(folder.toAbsolutePath().normalize().toString());
        }
        saveCatalog();
        invalidateCache();
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.addProperty("filesKept", true);
        result.addProperty("path", folder.toString());
        return result;
    }

    private JsonObject addChosenDeck(Path folder, String relinkId) throws FsException {
        String scannedId = deckIdForFolder(folder, folder.getFileName().toString());
        DeckEntry metadata = readEntry(scannedId, folder, folder.resolve("deck.json"));
        String id = relinkId == null ? scannedId : relinkId;
        if (!DECK_ID.matcher(id).matches()) throw bad("Deck id in deck.json is invalid");
        synchronized (lock) {
            for (Map.Entry<String, Path> entry : catalog.entrySet()) {
                if (entry.getValue().toAbsolutePath().normalize().equals(folder.toAbsolutePath().normalize()) && !entry.getKey().equals(id)) {
                    JsonObject duplicate = new JsonObject();
                    duplicate.addProperty("alreadyInLibrary", true);
                    return duplicate;
                }
            }
            if (catalog.containsKey(id) && relinkId == null) {
                JsonObject duplicate = new JsonObject();
                duplicate.addProperty("alreadyInLibrary", true);
                return duplicate;
            }
            catalog.put(id, folder);
            removedFolders.remove(folder.toAbsolutePath().normalize().toString());
        }
        saveCatalog();
        invalidateCache();
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        entry.addProperty("title", metadata.title);
        entry.addProperty("folder", folder.getFileName().toString());
        entry.addProperty("path", folder.toString());
        entry.addProperty("slides", metadata.slides);
        JsonArray added = new JsonArray();
        added.add(entry);
        JsonObject result = new JsonObject();
        result.addProperty("ok", true);
        result.add("added", added);
        return result;
    }

    /**
     * Copies a deck folder to {@code root/<copyId>} (deck.json last, patched
     * with the new id/title). A failed copy is cleaned up — never a half deck.
     */
    public JsonObject duplicateDeck(String deck, String copyId, String title) throws FsException {
        Path src = requireDeckDir(deck);
        if (copyId == null || !DECK_ID.matcher(copyId).matches()) {
            throw bad("Invalid copy id");
        }
        Path r = requireRootValid();
        Path dst = r.resolve(copyId);
        if (Files.exists(dst)) {
            throw new FsException(CODE_ALREADY_EXISTS, "a deck folder named \"" + copyId + "\" already exists");
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(src)) {
            files = walk.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            throw ioOf(e, "scan " + deck);
        }
        try {
            Files.createDirectories(dst);
            for (Path f : files) {
                String rel = src.relativize(f).toString().replace('\\', '/');
                if ("deck.json".equals(rel)) {
                    continue; // written last, patched
                }
                copyFileAtomic(f, resolveConfined(dst, rel));
            }
            String json = Files.readString(src.resolve("deck.json"), StandardCharsets.UTF_8);
            JsonObject deckJson;
            try {
                deckJson = JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception e) {
                deckJson = new JsonObject();
            }
            deckJson.addProperty("id", copyId);
            if (title != null && !title.isBlank()) {
                deckJson.addProperty("title", title);
            }
            deckJson.addProperty("modified", Instant.now().toString());
            atomicWriteBytes(dst.resolve("deck.json"),
                    (JsonParser.parseString(json).isJsonObject()
                            ? gsonPretty(deckJson) : deckJson.toString()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | FsException e) {
            deleteTreeBestEffort(dst);
            if (e instanceof FsException fe) {
                throw fe;
            }
            throw ioOf((IOException) e, "duplicate " + deck);
        }
        invalidateCache();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("id", copyId);
        return o;
    }

    private static String gsonPretty(JsonObject o) {
        return new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n";
    }

    /* ------------------------------------------------------------------ */
    /* fs.listFiles / fs.delete / fs.fileUrl                              */
    /* ------------------------------------------------------------------ */

    /** One directory level of a deck: [{name, size, modified, directory}]. */
    public JsonArray listFiles(String deck, String dir) throws FsException {
        Path base = requireDeckDir(deck);
        Path target = base;
        if (dir != null && !dir.isBlank()) {
            target = resolveConfined(base, dir);
            if (!Files.isDirectory(target)) {
                throw new FsException(CODE_NOT_FOUND, "Folder not found: " + dir);
            }
        }
        JsonArray arr = new JsonArray();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(target)) {
            for (Path p : ds) {
                names.add(p.getFileName().toString());
            }
        } catch (IOException e) {
            throw ioOf(e, "list " + dir);
        }
        names.sort(String::compareTo);
        for (String name : names) {
            Path p = target.resolve(name);
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            boolean isDir = Files.isDirectory(p);
            o.addProperty("directory", isDir);
            long size = 0;
            long mtime = 0;
            try {
                if (!isDir) {
                    size = Files.size(p);
                }
                mtime = Files.getLastModifiedTime(p).toMillis();
            } catch (IOException ignored) {
                // unreadable metadata — report zeros
            }
            o.addProperty("size", size);
            o.addProperty("modified", mtime);
            arr.add(o);
        }
        return arr;
    }

    /** Deletes one file (or a folder recursively) inside a deck. */
    public JsonObject delete(String deck, String path) throws FsException {
        Path base = requireDeckDir(deck);
        Path target = resolveConfined(base, path);
        if (Files.notExists(target)) {
            throw new FsException(CODE_NOT_FOUND, "File not found: " + path);
        }
        if (Files.isDirectory(target)) {
            deleteTreeVerified(target);
        } else {
            try {
                Files.delete(target);
            } catch (IOException e) {
                throw ioOf(e, "delete " + path);
            }
            if (Files.exists(target)) {
                throw new FsException(CODE_IO_ERROR, "file could not be deleted: " + path);
            }
        }
        invalidateCache();
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    /* ------------------------------------------------------------------ */
    /* shared write helpers (also used by the loopback file server)        */
    /* ------------------------------------------------------------------ */

    /**
     * Atomic write: temp file in the SAME folder (same volume → rename really
     * is atomic), full flush + fsync, then rename over the target. A crash
     * midway leaves the old file intact and a stray temp file — never a
     * half-written file. Only the target file is touched.
     */
    static void atomicWriteBytes(Path target, byte[] data) throws IOException {
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, ".chasse-tmp-", ".tmp");
        boolean moved = false;
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.write(data);
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            }
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    /** Streaming variant for uploads: body → temp file (fsync) → rename. */
    static Path beginTempFile(Path dir) throws IOException {
        Files.createDirectories(dir);
        return Files.createTempFile(dir, ".chasse-upload-", ".tmp");
    }

    static void finishTempFile(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Copies one file atomically (stream → temp + fsync → rename). */
    private static void copyFileAtomic(Path source, Path target) throws IOException {
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Files.createTempFile(dir, ".chasse-copy-", ".tmp");
        boolean moved = false;
        try {
            try (InputStream in = Files.newInputStream(source);
                 FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                byte[] buf = new byte[64 * 1024];
                int got;
                while ((got = in.read(buf)) > 0) {
                    out.write(buf, 0, got);
                }
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            }
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    static FsException ioOf(IOException e, String what) {
        if (e instanceof AccessDeniedException ade) {
            return new FsException(CODE_NO_OS_PERMISSION,
                    "no permission to " + what + " (the file may be open or locked): " + ade.getFile());
        }
        if (e instanceof NoSuchFileException nsf) {
            return new FsException(CODE_NOT_FOUND, "not found while trying to " + what + ": " + nsf.getFile());
        }
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new FsException(CODE_IO_ERROR, "could not " + what + ": " + msg);
    }

    /* ------------------------------------------------------------------ */
    /* deletion core                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Bottom-up delete; ok only once the tree is really gone. Failures keep
     * their real OS reason (locked/open files surface as NO_OS_PERMISSION,
     * anything else as IO_ERROR with the specific path).
     */
    private void deleteTreeVerified(Path dir) throws FsException {
        List<Path> order;
        try (Stream<Path> walk = Files.walk(dir)) {
            order = walk.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            throw ioOf(e, "scan " + dir.getFileName());
        }
        List<String> failures = new ArrayList<>();
        for (Path p : order) {
            try {
                Files.delete(p);
            } catch (IOException e) {
                failures.add(p.getFileName() + ": " + reasonOf(e));
            }
        }
        if (Files.exists(dir)) {
            String detail = failures.isEmpty() ? "" : " (" + String.join("; ", failures) + ")";
            throw new FsException(CODE_IO_ERROR,
                    "the folder could not be deleted completely" + detail);
        }
    }

    private static String reasonOf(IOException e) {
        if (e instanceof AccessDeniedException) {
            return "no permission (file may be open or locked)";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void deleteTreeBestEffort(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best effort — a partial failure just leaves files to retry
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
