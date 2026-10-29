package com.chasse.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for the git side of the bridge ({@link GitService}) — run
 * against a REAL git on real disk (a deck folder that is a repository plus a
 * bare "origin" in the temp dir), and skipped as a whole when git is not
 * installed on the machine running the tests.
 */
class GitServiceTest {

    @TempDir
    Path tempDir;

    private FsService fs;
    private GitService git;

    private static JsonObject deckFiles(String id) {
        JsonObject files = new JsonObject();
        JsonObject deck = new JsonObject();
        deck.addProperty("format", "smdeck");
        deck.addProperty("format_version", 1);
        deck.addProperty("id", id);
        deck.addProperty("title", id);
        deck.add("slides", new JsonArray());
        files.addProperty("deck.json", deck.toString());
        files.addProperty("theme.css", ":root { color: red; }");
        return files;
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(gitInstalled(), "git is not installed — GitService tests skipped");
        Path root = Files.createDirectories(tempDir.resolve("decks"));
        JsonObject cfg = new JsonObject();
        cfg.addProperty("decksRoot", root.toAbsolutePath().normalize().toString());
        cfg.addProperty("confirmedRoot", root.toAbsolutePath().normalize().toString());
        Files.writeString(tempDir.resolve("config.json"), cfg.toString());
        fs = new FsService(tempDir.resolve("config.json"));
        git = new GitService(fs);
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                             */
    /* ------------------------------------------------------------------ */

    private static boolean gitInstalled() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** runs git in {@code dir}; fails the test on a non-zero exit */
    private static String sh(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "git " + args[0] + " hung");
        assertEquals(0, p.exitValue(), "git " + String.join(" ", args) + " failed:\n" + out);
        return out.trim();
    }

    /** a deck whose folder is a repository on `main` with a test identity */
    private Path repoDeck(String id) throws Exception {
        fs.createDeck(id, deckFiles(id));
        Path dir = tempDir.resolve("decks/" + id);
        sh(dir, "init", "-q");
        sh(dir, "symbolic-ref", "HEAD", "refs/heads/main");
        sh(dir, "config", "user.name", "Chasse Test");
        sh(dir, "config", "user.email", "test@example.invalid");
        return dir;
    }

    private Path bareOrigin(Path deckDir) throws Exception {
        Path bare = tempDir.resolve("origin.git");
        sh(tempDir, "init", "-q", "--bare", bare.toString());
        sh(deckDir, "remote", "add", "origin", bare.toString());
        return bare;
    }

    /* ------------------------------------------------------------------ */
    /* status                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    void plainDeckIsNotConnected() throws Exception {
        fs.createDeck("plain", deckFiles("plain"));
        JsonObject st = git.status("plain");
        assertFalse(st.get("connected").getAsBoolean());
        assertEquals("no-repo", st.get("reason").getAsString());
    }

    @Test
    void unknownDeckIsNotFound() {
        FsService.FsException e = assertThrows(FsService.FsException.class, () -> git.status("ghost"));
        assertEquals(FsService.CODE_NOT_FOUND, e.code);
    }

    @Test
    void repositoryOnMainIsConnectedAndDirtyUntilCommitted() throws Exception {
        Path dir = repoDeck("repo");
        JsonObject st = git.status("repo");
        assertTrue(st.get("connected").getAsBoolean());
        assertEquals("main", st.get("branch").getAsString());
        assertTrue(st.get("dirty").getAsBoolean(), "deck.json and theme.css are untracked");

        sh(dir, "add", "-A");
        sh(dir, "commit", "-q", "-m", "first");
        assertFalse(git.status("repo").get("dirty").getAsBoolean());

        fs.writeText("repo", "slides/s1.tsx", "export default null");
        assertTrue(git.status("repo").get("dirty").getAsBoolean(), "a new untracked file makes it dirty");
    }

    @Test
    void otherBranchIsNotConnected() throws Exception {
        Path dir = repoDeck("feature");
        sh(dir, "symbolic-ref", "HEAD", "refs/heads/feature");
        JsonObject st = git.status("feature");
        assertFalse(st.get("connected").getAsBoolean());
        assertEquals("branch", st.get("reason").getAsString());
        assertEquals("feature", st.get("branch").getAsString());
    }

    @Test
    void detachedHeadIsNotConnected() throws Exception {
        Path dir = repoDeck("detached");
        sh(dir, "add", "-A");
        sh(dir, "commit", "-q", "-m", "first");
        sh(dir, "checkout", "-q", "--detach");
        JsonObject st = git.status("detached");
        assertFalse(st.get("connected").getAsBoolean());
        assertEquals("detached", st.get("reason").getAsString());
    }

    /* ------------------------------------------------------------------ */
    /* publish                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    void commitMessageIsGeneratedFromTheClock() {
        assertEquals("chasse: update 2026-10-29 17:43:05",
                GitService.commitMessageFor(LocalDateTime.of(2026, 10, 29, 17, 43, 5)));
        assertEquals("chasse: update 2026-01-02 03:04:05",
                GitService.commitMessageFor(LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
    }

    @Test
    void publishCommitsAndPushesToOrigin() throws Exception {
        Path dir = repoDeck("pub");
        Path bare = bareOrigin(dir);

        JsonObject r = git.publish("pub");
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        assertTrue(r.get("committed").getAsBoolean());
        String commit = r.get("commit").getAsString();
        assertTrue(commit.matches("chasse: update \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), commit);
        assertFalse(r.has("error"));

        // the remote has exactly that commit on main, the tree is clean, upstream is set
        assertEquals(commit, sh(bare, "log", "-1", "--format=%s", "main"));
        assertFalse(git.status("pub").get("dirty").getAsBoolean());
        assertEquals("origin/main", sh(dir, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"));

        // nothing changed → nothing committed, push is a no-op success
        JsonObject again = git.publish("pub");
        assertTrue(again.get("ok").getAsBoolean(), again.toString());
        assertFalse(again.get("committed").getAsBoolean());
        assertFalse(again.has("commit"));
        assertEquals("1", sh(bare, "rev-list", "--count", "main"));
    }

    @Test
    void publishWithoutOriginCommitsLocallyAndExplains() throws Exception {
        repoDeck("local");
        JsonObject r = git.publish("local");
        assertFalse(r.get("ok").getAsBoolean());
        assertTrue(r.get("committed").getAsBoolean(), "the commit happens before the push fails");
        assertTrue(r.get("error").getAsString().contains("origin"), r.get("error").getAsString());
        assertFalse(git.status("local").get("dirty").getAsBoolean());
    }

    @Test
    void publishRefusesADeckThatIsNotConnected() throws Exception {
        fs.createDeck("plain", deckFiles("plain"));
        FsService.FsException e = assertThrows(FsService.FsException.class, () -> git.publish("plain"));
        assertEquals(GitService.CODE_NOT_CONNECTED, e.code);
        assertFalse(Files.exists(tempDir.resolve("decks/plain/.git")), "publish never creates a repository");
    }
}
