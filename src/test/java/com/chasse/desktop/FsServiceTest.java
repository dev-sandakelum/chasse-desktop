package com.chasse.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the decks-folder storage layer ({@link FsService}) — the
 * Java side of the "fs" bridge capability.
 *
 * <p>Covers the security core (path confinement, symlink refusal, traversal),
 * the deck lifecycle (create/read/write/delete/duplicate), the atomic-write
 * contract (deck.json last, no temp files left behind), limits and the
 * contract error codes.</p>
 *
 * <p>What these tests deliberately do NOT cover (needs a display): the native
 * folder chooser dialogs ({@code chooseRoot}). The bridge transport itself is
 * exercised by the web repo's bridge-contract smoke test, whose mock
 * implements the same Java-side contract shapes.</p>
 */
class FsServiceTest {

    @TempDir
    Path tempDir;

    private FsService fs;

    // A minimal deck.json payload that the service accepts. The deck id MUST
    // match the folder it is created in — the web side always writes it that
    // way (desktop-store patches deck.json before fs.createDeck), and the
    // library is keyed by the deck.json id, so two decks sharing one id would
    // collapse into a single library entry.
    private static JsonObject deckFiles(String id, String title) {
        JsonObject files = new JsonObject();
        JsonObject deck = new JsonObject();
        deck.addProperty("format", "smdeck");
        deck.addProperty("format_version", 1);
        deck.addProperty("id", id);
        deck.addProperty("title", title);
        deck.add("slides", new JsonArray());
        files.addProperty("deck.json", deck.toString());
        files.addProperty("theme.css", ":root { color: red; }");
        return files;
    }

    private static JsonObject deckFiles(String title) {
        return deckFiles("demo", title);
    }

    private static String code(FsService.FsException e) {
        return e.code;
    }

    @BeforeEach
    void setUp() throws Exception {
        setRoot(tempDir.resolve("decks"));
    }

    /** Creates a test-only FsService bound to a temp config file + chosen root. */
    private void setRoot(Path root) throws Exception {
        Files.createDirectories(root);
        JsonObject cfg = new JsonObject();
        cfg.addProperty("decksRoot", root.toAbsolutePath().normalize().toString());
        cfg.addProperty("confirmedRoot", root.toAbsolutePath().normalize().toString());
        Files.writeString(tempDir.resolve("config.json"), cfg.toString());
        fs = new FsService(tempDir.resolve("config.json"));
    }

    /* ------------------------------------------------------------------ */
    /* getRoot / config persistence                                        */
    /* ------------------------------------------------------------------ */

    @Test
    void freshServiceIsUnconfigured() throws Exception {
        FsService fresh = new FsService(tempDir.resolve("fresh-config.json"));
        JsonObject root = fresh.getRoot();
        assertFalse(root.get("configured").getAsBoolean());
        assertEquals("", root.get("path").getAsString());
        assertFalse(root.get("valid").getAsBoolean());
    }

    @Test
    void rootSurvivesReload() throws Exception {
        JsonObject root = fs.getRoot();
        assertTrue(root.get("configured").getAsBoolean());
        assertTrue(root.get("valid").getAsBoolean());
        assertEquals(tempDir.resolve("decks").toAbsolutePath().normalize().toString(),
                root.get("path").getAsString());
    }

    /* ------------------------------------------------------------------ */
    /* path confinement (security core)                                    */
    /* ------------------------------------------------------------------ */

    @Test
    void rejectsTraversalPaths() {
        assertThrows(FsService.FsException.class, () -> FsService.checkRelPath("../outside"));
        assertThrows(FsService.FsException.class, () -> FsService.checkRelPath("slides/../../outside"));
        assertThrows(FsService.FsException.class, () -> FsService.checkRelPath(".."));
    }

    @Test
    void chunkUploadAtomicallyPublishesAssetAndReadFallbackIsBounded() throws Exception {
        fs.createDeck("demo", deckFiles("Demo"));
        String uploadId = fs.beginAssetUpload("demo", "assets/space image.png");
        fs.writeAssetChunk(uploadId, java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        assertFalse(Files.exists(tempDir.resolve("decks/demo/assets/space image.png")));
        JsonObject result = fs.finishAssetUpload(uploadId);
        assertEquals(3, result.get("size").getAsInt());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(tempDir.resolve("decks/demo/assets/space image.png")));
        assertEquals(java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}),
                fs.readAssetBase64("demo", "assets/space image.png"));
    }

    @Test
    void abortedChunkUploadLeavesNoDestinationOrTempFile() throws Exception {
        fs.createDeck("demo", deckFiles("Demo"));
        String uploadId = fs.beginAssetUpload("demo", "assets/aborted.png");
        fs.writeAssetChunk(uploadId, java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        fs.abortAssetUpload(uploadId);
        assertFalse(Files.exists(tempDir.resolve("decks/demo/assets/aborted.png")));
        assertEquals(0, fs.listFiles("demo", "assets").size());
    }

    @Test
    void rejectsIllegalPaths() {
        assertThrows(FsService.FsException.class, () -> FsService.checkRelPath(""));
        assertThrows(FsService.FsException.class, () -> FsService.checkRelPath("/absolute"));
        assertThrows(FsService.FsException.class, () -> FsService.checkRelPath("back\\slash"));
        assertThrows(FsService.FsException.class, () -> FsService.checkRelPath("dot/./segment"));
        assertThrows(FsService.FsException.class, () -> FsService.checkRelPath("null\0byte"));
        assertThrows(FsService.FsException.class, () -> FsService.checkRelPath("a/".repeat(120) + "x")); // > 240 chars
    }

    @Test
    void writeThroughSymlinkIsRefused() throws Exception {
        JsonObject created = fs.createDeck("symlinked", deckFiles("T"));
        assertTrue(created.get("ok").getAsBoolean());
        Path deckDir = tempDir.resolve("decks/symlinked");
        Path outsideTarget = Files.createDirectories(tempDir.resolve("outside-target"));
        Files.createSymbolicLink(deckDir.resolve("evil.txt"), outsideTarget);
        FsService.FsException e = assertThrows(FsService.FsException.class,
                () -> fs.writeText("symlinked", "evil.txt", "nope"));
        assertEquals("BAD_REQUEST", code(e));
    }

    @Test
    void symlinkEscapeViaMissingFileIsRefused() throws Exception {
        // A not-yet-existing path under a SYMLINKED directory must also fail:
        // resolveConfined checks the nearest existing ancestor's real path.
        fs.createDeck("linkparent", deckFiles("T"));
        Path deckDir = tempDir.resolve("decks/linkparent");
        Path outsideTarget = Files.createDirectories(tempDir.resolve("outside-target"));
        Files.createSymbolicLink(deckDir.resolve("assets"), outsideTarget);
        FsService.FsException e = assertThrows(FsService.FsException.class,
                () -> fs.writeText("linkparent", "assets/new.png", "x"));
        assertEquals("BAD_REQUEST", code(e));
    }

    /* ------------------------------------------------------------------ */
    /* deck lifecycle                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void createDeckWritesDeckJsonLastAndReadsBack() throws Exception {
        JsonObject created = fs.createDeck("life", deckFiles("Life"));
        assertTrue(created.get("ok").getAsBoolean());
        assertEquals("life", created.get("id").getAsString());
        assertTrue(Files.isRegularFile(tempDir.resolve("decks/life/deck.json")));
        assertTrue(fs.readText("life", "deck.json").get("text").getAsString().contains("\"title\":\"Life\""));
        assertTrue(fs.readText("life", "theme.css").get("text").getAsString().contains("red"));
    }

    @Test
    void createDeckRequiresDeckJson() {
        JsonObject files = new JsonObject();
        files.addProperty("theme.css", "x");
        FsService.FsException e = assertThrows(FsService.FsException.class,
                () -> fs.createDeck("nodeckjson", files));
        assertEquals("BAD_REQUEST", code(e));
    }

    @Test
    void createDeckRejectsTraversalFileKeysAndWritesNothing() throws Exception {
        JsonObject files = deckFiles("T");
        files.addProperty("../escape.txt", "nope");
        FsService.FsException e = assertThrows(FsService.FsException.class,
                () -> fs.createDeck("escapee", files));
        assertEquals("BAD_REQUEST", code(e));
        assertFalse(Files.exists(tempDir.resolve("decks/escapee"))); // nothing left behind
        assertFalse(Files.exists(tempDir.resolve("escape.txt")));
    }

    @Test
    void createDeckRefusesDuplicatesAndBadIds() throws Exception {
        fs.createDeck("dup", deckFiles("One"));
        FsService.FsException e = assertThrows(FsService.FsException.class,
                () -> fs.createDeck("dup", deckFiles("Two")));
        assertEquals("ALREADY_EXISTS", code(e));

        FsService.FsException badId = assertThrows(FsService.FsException.class,
                () -> fs.createDeck("BAD ID", deckFiles("T")));
        assertEquals("BAD_REQUEST", code(badId));
        FsService.FsException badId2 = assertThrows(FsService.FsException.class,
                () -> fs.createDeck("..", deckFiles("T")));
        assertEquals("BAD_REQUEST", code(badId2));
    }

    @Test
    void overwriteCreatesHistoryBackupAndKeepsWriting() throws Exception {
        // regression: the very first overwrite of a file used to fail with
        // "path not accessible: <snapshot dir>" because backupBeforeWrite()
        // handed a not-yet-created .history/<ts-uuid> folder to
        // resolveConfined(), which resolves its base with toRealPath().
        fs.createDeck("hist", deckFiles("hist", "History"));
        fs.writeText("hist", "slides/s.tsx", "// v1");
        fs.writeText("hist", "slides/s.tsx", "// v2");   // first overwrite → backup
        fs.writeText("hist", "slides/s.tsx", "// v3");   // second overwrite → another backup
        assertEquals("// v3", fs.readText("hist", "slides/s.tsx").get("text").getAsString());
        Path history = tempDir.resolve("decks/hist/.history");
        assertTrue(Files.isDirectory(history), ".history exists after overwrites");
        int backups = 0;
        try (var s = Files.list(history)) {
            for (Path snap : s.filter(Files::isDirectory).toList()) {
                assertTrue(Files.isRegularFile(snap.resolve("slides/s.tsx")),
                        "backup carries the pre-write content");
                backups++;
            }
        }
        assertTrue(backups >= 2, "each overwrite left a backup (found " + backups + ")");
        // the CURRENT file is v3 — backups hold older content
        boolean sawOldContent = false;
        try (var s = Files.list(history)) {
            for (Path snap : s.filter(Files::isDirectory).toList()) {
                String content = Files.readString(snap.resolve("slides/s.tsx"));
                if (content.equals("// v1") || content.equals("// v2")) sawOldContent = true;
            }
        }
        assertTrue(sawOldContent, "a backup preserves the pre-write version");
    }

    @Test
    void writeTextRoundTripsWithNoTempFilesLeftBehind() throws Exception {
        fs.createDeck("w", deckFiles("W"));
        fs.writeText("w", "slides/a.tsx", "export default function Slide() {}");
        assertEquals("export default function Slide() {}",
                fs.readText("w", "slides/a.tsx").get("text").getAsString());
        // overwrite + read again — the atomic rename must leave a consistent file
        fs.writeText("w", "slides/a.tsx", "// v2");
        assertEquals("// v2", fs.readText("w", "slides/a.tsx").get("text").getAsString());
        try (var s = Files.list(tempDir.resolve("decks/w"))) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().contains(".chasse-tmp")),
                    "atomic write left temp files behind");
        }
    }

    @Test
    void writeTextEnforcesSizeLimit() throws Exception {
        fs.createDeck("big", deckFiles("B"));
        String huge = "x".repeat(2 * 1024 * 1024 + 1);
        FsService.FsException e = assertThrows(FsService.FsException.class,
                () -> fs.writeText("big", "slides/huge.tsx", huge));
        assertEquals("TOO_LARGE", code(e));
    }

    @Test
    void missingDeckOrFileIsNotFound() throws Exception {
        fs.createDeck("present", deckFiles("P"));
        FsService.FsException e = assertThrows(FsService.FsException.class,
                () -> fs.readText("present", "slides/absent.tsx"));
        assertEquals("NOT_FOUND", code(e));
        FsService.FsException e2 = assertThrows(FsService.FsException.class,
                () -> fs.readText("ghost", "deck.json"));
        assertEquals("NOT_FOUND", code(e2));
    }

    @Test
    void deleteRemovesTheFolderVerified() throws Exception {
        fs.createDeck("gone", deckFiles("G"));
        fs.writeText("gone", "slides/x.tsx", "// x");
        JsonObject res = fs.deleteDeck("gone");
        assertTrue(res.get("ok").getAsBoolean());
        assertFalse(Files.exists(tempDir.resolve("decks/gone")));
        FsService.FsException e = assertThrows(FsService.FsException.class,
                () -> fs.readText("gone", "deck.json"));
        assertEquals("NOT_FOUND", code(e));
    }

    @Test
    void removeFromLibraryKeepsDeckFilesAndPreventsAutomaticReadoption() throws Exception {
        fs.createDeck("kept", deckFiles("Kept"));
        Path deckDir = tempDir.resolve("decks/kept");
        String before = Files.readString(deckDir.resolve("deck.json"));

        JsonObject result = fs.removeFromLibrary("kept");

        assertTrue(result.get("ok").getAsBoolean());
        assertTrue(result.get("filesKept").getAsBoolean());
        assertEquals(deckDir.toString(), result.get("path").getAsString());
        assertTrue(Files.isDirectory(deckDir));
        assertEquals(before, Files.readString(deckDir.resolve("deck.json")));
        assertEquals(0, fs.listDecks().size());
        FsService.FsException e = assertThrows(FsService.FsException.class,
                () -> fs.readText("kept", "deck.json"));
        assertEquals("NOT_FOUND", code(e));
    }

    @Test
    void catalogAllowsOnlyTheLinkedFolderOutsideTheDefaultRoot() throws Exception {
        Path external = Files.createDirectories(tempDir.resolve("external/Linked Folder"));
        Files.writeString(external.resolve("deck.json"), deckFiles("Linked").get("deck.json").getAsString());
        JsonObject catalog = new JsonObject();
        JsonArray decks = new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("id", "linked");
        entry.addProperty("folder", external.toRealPath().toString());
        decks.add(entry);
        catalog.add("decks", decks);
        catalog.add("removedFolders", new JsonArray());
        Files.writeString(tempDir.resolve("catalog.json"), catalog.toString());
        fs = new FsService(tempDir.resolve("config.json"));

        assertEquals("Linked", fs.listDecks().get(0).getAsJsonObject().get("title").getAsString());
        fs.writeText("linked", "slides/linked.tsx", "export default null");
        assertEquals("export default null", Files.readString(external.resolve("slides/linked.tsx")));
    }

    @Test
    void duplicatePatchesDeckJsonAndKeepsFiles() throws Exception {
        fs.createDeck("orig", deckFiles("Original"));
        fs.writeText("orig", "slides/s1.tsx", "// slide");
        fs.writeText("orig", "assets/pic.png", "not really png");
        JsonObject res = fs.duplicateDeck("orig", "copy", "Copy title");
        assertTrue(res.get("ok").getAsBoolean());
        assertEquals("copy", res.get("id").getAsString());
        String json = fs.readText("copy", "deck.json").get("text").getAsString();
        // duplicateDeck re-serializes with Gson pretty-printing — parse, don't substring-match
        JsonObject patched = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertEquals("copy", patched.get("id").getAsString());
        assertEquals("Copy title", patched.get("title").getAsString());
        assertEquals("// slide", fs.readText("copy", "slides/s1.tsx").get("text").getAsString());
        assertEquals("not really png", fs.readText("copy", "assets/pic.png").get("text").getAsString());
    }

    /* ------------------------------------------------------------------ */
    /* listing + scan                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void listDecksFindsOnlyRealDeckFolders() throws Exception {
        fs.createDeck("alpha", deckFiles("alpha", "Alpha"));
        fs.createDeck("beta", deckFiles("beta", "Beta"));
        Files.createDirectories(tempDir.resolve("decks/not-a-deck")); // no deck.json
        Files.createDirectories(tempDir.resolve("decks/UPPER"));      // invalid id
        JsonArray decks = fs.listDecks();
        assertEquals(2, decks.size());
        // beta was written last — the list is newest-first
        assertEquals("beta", decks.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("Beta", decks.get(0).getAsJsonObject().get("title").getAsString());
    }

    @Test
    void listFilesWalksSubdirectories() throws Exception {
        fs.createDeck("walk", deckFiles("W"));
        fs.writeText("walk", "slides/a.tsx", "// a");
        fs.writeText("walk", "notes/a.md", "# note");
        JsonArray root = fs.listFiles("walk", "");
        // root level: deck.json, theme.css, slides/, notes/
        assertEquals(4, root.size());

        JsonArray slides = fs.listFiles("walk", "slides");
        assertEquals(1, slides.size());
        assertEquals("a.tsx", slides.get(0).getAsJsonObject().get("name").getAsString());
    }

    /* ------------------------------------------------------------------ */
    /* root health (the "cannot save" guards)                              */
    /* ------------------------------------------------------------------ */

    @Test
    void missingRootIsFolderMissing() throws Exception {
        setRoot(tempDir.resolve("vanishing"));
        Files.delete(tempDir.resolve("vanishing"));
        FsService.FsException e = assertThrows(FsService.FsException.class, () -> fs.listDecks());
        assertEquals("FOLDER_MISSING", code(e));
    }

    @Test
    void unconfiguredRootIsFolderNotSet() throws Exception {
        FsService fresh = new FsService(tempDir.resolve("unconfigured.json"));
        FsService.FsException e = assertThrows(FsService.FsException.class, () -> fresh.listDecks());
        assertEquals("FOLDER_NOT_SET", code(e));
    }

    @Test
    void getRootIsValidatedWithoutThrowing() throws Exception {
        setRoot(tempDir.resolve("gone-root"));
        Files.delete(tempDir.resolve("gone-root"));
        JsonObject root = fs.getRoot(); // never throws — it IS the health report
        assertTrue(root.get("configured").getAsBoolean());
        assertFalse(root.get("valid").getAsBoolean());
    }

    /* ------------------------------------------------------------------ */
    /* snapshot dir hygiene                                                 */
    /* ------------------------------------------------------------------ */

    @Test
    void hiddenSnapshotFilesStayInsideTheDeck() throws Exception {
        fs.createDeck("snaps", deckFiles("S"));
        String payload = "{\"files\":{\"deck.json\":\"{}\"}}";
        fs.writeText("snaps", ".stagesmith/snapshots/2024-01-01--before.json", payload);
        String read = fs.readText("snaps", ".stagesmith/snapshots/2024-01-01--before.json")
                .get("text").getAsString();
        assertEquals(payload, read);
    }
}
