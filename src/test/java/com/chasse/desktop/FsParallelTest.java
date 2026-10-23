package com.chasse.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency-correctness tests for the parallel loading paths. The original
 * suite is single-threaded, so none of these invariants were covered before.
 */
class FsParallelTest {

    @TempDir
    Path tempDir;

    private FsService fs;

    @BeforeEach
    void setUp() throws Exception {
        Path root = tempDir.resolve("decks");
        Files.createDirectories(root);
        JsonObject cfg = new JsonObject();
        cfg.addProperty("decksRoot", root.toAbsolutePath().normalize().toString());
        cfg.addProperty("confirmedRoot", root.toAbsolutePath().normalize().toString());
        Files.writeString(tempDir.resolve("config.json"), cfg.toString(), StandardCharsets.UTF_8);
        fs = new FsService(tempDir.resolve("config.json"));
    }

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

    /** Many concurrent listDecks must all see a consistent, complete list. */
    @Test
    void concurrentScansAgree() throws Exception {
        for (int i = 0; i < 30; i++) {
            fs.createDeck(String.format("deck-%02d", i), deckFiles(String.format("deck-%02d", i), "T" + i));
        }
        int threads = 8;
        List<Thread> workers = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            workers.add(new Thread(() -> {
                try {
                    for (int round = 0; round < 10; round++) {
                        JsonArray decks = fs.listDecks();
                        if (decks.size() != 30) {
                            failures.incrementAndGet();
                        }
                        for (var el : decks) {
                            JsonObject o = el.getAsJsonObject();
                            if (!o.has("id") || !o.has("title") || !o.has("modified")) {
                                failures.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "scan-test-" + t));
        }
        workers.forEach(Thread::start);
        assertTrue(done.await(30, TimeUnit.SECONDS), "scan workers did not finish");
        assertEquals(0, failures.get(), "concurrent scans disagreed or failed");
    }

    /** listFiles keeps its sorted, complete output under parallel stats. */
    @Test
    void listFilesOrderIsStableUnderParallelism() throws Exception {
        fs.createDeck("order", deckFiles("order", "Order"));
        for (int i = 0; i < 120; i++) {
            fs.writeText("order", String.format("assets/file-%03d.png", i), "x");
        }
        for (int round = 0; round < 5; round++) {
            JsonArray files = fs.listFiles("order", "assets");
            assertEquals(120, files.size());
            String prev = null;
            for (var el : files) {
                String name = el.getAsJsonObject().get("name").getAsString();
                if (prev != null) {
                    assertTrue(prev.compareTo(name) < 0, "listing order broke: " + prev + " !< " + name);
                }
                prev = name;
            }
        }
    }

    /** Reads, writes, scans and deletes on DIFFERENT decks must not corrupt each other. */
    @Test
    void mixedConcurrentOperationsStayIsolated() throws Exception {
        for (int i = 0; i < 6; i++) {
            fs.createDeck("mix-" + i, deckFiles("mix-" + i, "Mix " + i));
        }
        int threads = 12;
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            new Thread(() -> {
                try {
                    String deck = "mix-" + (id % 6);
                    for (int round = 0; round < 5; round++) {
                        switch (id % 3) {
                            case 0 -> fs.writeText(deck, "slides/s" + round + ".tsx", "v" + round);
                            case 1 -> {
                                fs.writeText(deck, "slides/s" + round + ".tsx", "v" + round);
                                String back = fs.readText(deck, "slides/s" + round + ".tsx")
                                        .get("text").getAsString();
                                if (!back.equals("v" + round)) failures.incrementAndGet();
                            }
                            default -> fs.listDecks();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "mix-test-" + t).start();
        }
        assertTrue(done.await(60, TimeUnit.SECONDS), "mixed workers did not finish");
        assertEquals(0, failures.get());
    }

    /** One broken deck.json never poisons a parallel scan. */
    @Test
    void scanToleratesBrokenDeckJsonInParallel() throws Exception {
        fs.createDeck("good-1", deckFiles("good-1", "One"));
        fs.createDeck("good-2", deckFiles("good-2", "Two"));
        // a deck folder with an unparsable deck.json — tolerated by contract
        Files.createDirectories(tempDir.resolve("decks/broken"));
        Files.writeString(tempDir.resolve("decks/broken/deck.json"), "{ not json !!!", StandardCharsets.UTF_8);
        JsonArray decks = fs.listDecks();
        assertEquals(3, decks.size(), "broken deck must still be listed (folder-name fallback)");
        for (int round = 0; round < 5; round++) {
            assertEquals(3, fs.listDecks().size());
        }
    }

    /** parallelMap: results in input order, failures isolated, cancel honoured. */
    @Test
    void parallelMapOrderErrorsAndCancellation() throws Exception {
        List<Integer> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) items.add(i);

        // order + values
        List<String> out = WorkerPools.parallelMap(items, 8, WorkerPools.NEVER_CANCELLED,
                i -> "v" + i, null);
        assertEquals(200, out.size());
        for (int i = 0; i < 200; i++) assertEquals("v" + i, out.get(i));

        // one failure never stops the others
        List<Throwable> errors = new ArrayList<>();
        List<String> out2 = WorkerPools.parallelMap(items, 8, WorkerPools.NEVER_CANCELLED,
                i -> {
                    if (i == 7) throw new IllegalStateException("boom");
                    return "v" + i;
                }, errors);
        assertEquals(1, errors.size());
        assertNull(out2.get(7));
        assertEquals("v0", out2.get(0));
        assertEquals("v199", out2.get(199));

        // per-task cancellation stops the work early but keeps completed slots
        WorkerPools.Flag cancel = new WorkerPools.Flag();
        AtomicInteger applied = new AtomicInteger();
        List<String> out3 = WorkerPools.parallelMap(items, 4, cancel,
                i -> {
                    int n = applied.incrementAndGet();
                    if (n == 10) cancel.cancel();
                    return "v" + i;
                }, null);
        assertTrue(applied.get() < 200, "cancellation should stop early (applied=" + applied.get() + ")");
    }

    /** The upload TTL reaper aborts abandoned sessions and frees the quota. */
    @Test
    void abandonedUploadsAreReaped() throws Exception {
        fs.createDeck("reap", deckFiles("reap", "Reap"));
        String id = fs.beginAssetUpload("reap", "assets/big.png");
        fs.writeAssetChunk(id, java.util.Base64.getEncoder().encodeToString(new byte[64]));
        // simulate abandonment: age the session beyond the TTL
        java.lang.reflect.Field f = fs.getClass().getDeclaredField("assetUploads");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> uploads = (java.util.Map<String, Object>) f.get(fs);
        Object up = uploads.get(id);
        assertNotNull(up);
        java.lang.reflect.Field last = up.getClass().getDeclaredField("lastUsed");
        last.setAccessible(true);
        last.setLong(up, System.currentTimeMillis() - 11 * 60_000L); // 11 min ago
        // run the reaper logic directly (the scheduled cadence is 60 s)
        java.lang.reflect.Method reap = fs.getClass().getDeclaredMethod("reapAbandonedUploads");
        reap.setAccessible(true);
        reap.invoke(fs);
        assertTrue(uploads.isEmpty(), "abandoned session must be reaped");
        assertFalse(Files.exists(tempDir.resolve("decks/reap/assets/big.png")));
        // a new session is accepted again (quota/limits were freed)
        String id2 = fs.beginAssetUpload("reap", "assets/next.png");
        fs.writeAssetChunk(id2, java.util.Base64.getEncoder().encodeToString(new byte[]{9}));
        fs.finishAssetUpload(id2);
        assertTrue(Files.exists(tempDir.resolve("decks/reap/assets/next.png")));
    }
}
