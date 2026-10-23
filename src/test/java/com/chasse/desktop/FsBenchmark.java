package com.chasse.desktop;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loading-path benchmark for FsService (NOT part of the regular test suite —
 * it only runs when {@code -Dbench=1} is set, so plain {@code mvn test} skips
 * it). Measures the operations the loading path is dominated by:
 *
 * <ul>
 *   <li>{@code listDecks} — the deck-folder scan (cold: after cache
 *       invalidation; warm: repeat within the TTL window)</li>
 *   <li>{@code listFiles} — one directory level with per-entry metadata</li>
 *   <li>{@code duplicateDeck} — full deck copy (the heaviest deck op)</li>
 *   <li>{@code createDeck} — bulk file creation</li>
 * </ul>
 *
 * Run: {@code mvn test -Dtest=FsBenchmark -Dbench=1}
 */
class FsBenchmark {

    private static final int DECKS = 120;
    private static final int SLIDES_IN_BIG = 80;
    private static final int ASSETS_IN_BIG = 60;
    private static final int ASSET_KB = 512;
    private static final int SMALL_DECK_SLIDES = 12;

    @Test
    void bench() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getProperty("bench")),
                "benchmark disabled (run with -Dbench=1)");

        Path root = Files.createTempDirectory("chasse-bench-");
        JsonObject cfg = new JsonObject();
        cfg.addProperty("decksRoot", root.toAbsolutePath().normalize().toString());
        cfg.addProperty("confirmedRoot", root.toAbsolutePath().normalize().toString());
        Path configFile = root.resolve("../chasse-bench-config.json").normalize();
        Files.writeString(configFile, cfg.toString(), StandardCharsets.UTF_8);
        FsService fs = new FsService(configFile);
        buildCorpus(root);

        System.out.printf("%n=== FsBenchmark (cores=%d) ===%n",
                Runtime.getRuntime().availableProcessors());

        // ── listDecks: cold (fresh scan) vs warm (TTL cache hit) ──────────
        for (int i = 0; i < 3; i++) {
            invalidate(fs);
            long t = time(() -> { try { fs.listDecks(); } catch (Exception e) { throw new RuntimeException(e); } });
            System.out.printf("listDecks  cold   #%d: %6d ms%n", i + 1, t);
        }
        for (int i = 0; i < 3; i++) {
            long t = time(() -> { try { fs.listDecks(); } catch (Exception e) { throw new RuntimeException(e); } });
            System.out.printf("listDecks  warm   #%d: %6d ms%n", i + 1, t);
        }

        // ── listFiles on the big deck's assets folder ──────────────────────
        for (int i = 0; i < 3; i++) {
            long t = time(() -> { try { fs.listFiles("deck-big", "assets"); } catch (Exception e) { throw new RuntimeException(e); } });
            System.out.printf("listFiles  assets #%d: %6d ms%n", i + 1, t);
        }

        // ── duplicateDeck of the big deck (~11 MB, 100+ files) ────────────
        for (int i = 0; i < 3; i++) {
            final String copy = "bench-copy-" + i;
            final String title = "Copy " + i;
            long t = time(() -> { try { fs.duplicateDeck("deck-big", copy, title); } catch (Exception e) { throw new RuntimeException(e); } });
            System.out.printf("duplicate  big    #%d: %6d ms%n", i + 1, t);
            fs.deleteDeck(copy);
        }

        // ── createDeck with 300 files ──────────────────────────────────────
        for (int i = 0; i < 3; i++) {
            JsonObject files = new JsonObject();
            for (int f = 0; f < 300; f++) {
                files.addProperty("slides/slide-" + f + ".tsx", slideSource(f));
            }
            final String deck = "bench-create-" + i;
            files.addProperty("deck.json", deckJson(deck));
            long t = time(() -> { try { fs.createDeck(deck, files); } catch (Exception e) { throw new RuntimeException(e); } });
            System.out.printf("createDeck 300f   #%d: %6d ms%n", i + 1, t);
            fs.deleteDeck(deck);
        }

        // ── simulated real-disk latency (HDD + antivirus model) ────────────
        // tmpfs makes every file op ~10 µs, so the parallel machinery cannot
        // show its real benefit there. A deck.json read on a spinning disk
        // with antivirus on-access scanning costs 1–5 ms of LATENCY (not
        // throughput) — this section models that and measures the fan-out
        // primitive the scan/copy/delete paths all use.
        int decks = 120;
        long perOpMs = Long.getLong("bench.latencyMs", 2);
        System.out.printf("%n-- simulated disk latency: %d decks x %d ms/op --%n", decks, perOpMs);
        long seq = time(() -> simulate(decks, perOpMs, 1));
        System.out.printf("scan mechanism  sequential (old):   %6d ms%n", seq);
        for (int p : new int[]{2, 4, 6, 8, 16}) {
            long par = time(() -> simulate(decks, perOpMs, p));
            System.out.printf("scan mechanism  parallel(%d): %6d ms  (speedup %.2fx)%n",
                    p, par, (double) seq / par);
        }
        System.out.println("=== end ===%n");
    }

    /** One simulated per-deck file operation with injected latency. */
    private static void simulate(int items, long perOpMs, int parallelism) throws Exception {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < items; i++) list.add(i);
        WorkerPools.parallelMap(list, parallelism, WorkerPools.NEVER_CANCELLED, i -> {
            Thread.sleep(perOpMs);
            return Boolean.TRUE;
        }, null);
    }

    /* ------------------------------------------------------------------ */

    private interface Checked { void run() throws Exception; }

    private static long time(Checked c) {
        long start = System.nanoTime();
        try {
            c.run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static void invalidate(FsService fs) throws Exception {
        // a write through the service invalidates the scan cache
        fs.writeText("deck-big", "bench-invalidate.txt", "x");
        fs.delete("deck-big", "bench-invalidate.txt");
    }

    private static void setRoot(FsService fs, Path root) throws Exception {
        JsonObject cfg = new JsonObject();
        cfg.addProperty("decksRoot", root.toString());
        cfg.addProperty("confirmedRoot", root.toString());
        Path configFile = root.resolveSibling(root.getFileName() + "-config.json");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, cfg.toString(), StandardCharsets.UTF_8);
        // FsService(Path) constructor already loaded the config BEFORE we wrote
        // it, so reflect the change the honest way: new instance on same config
        // (this benchmark re-reads through the public constructor seam)
    }

    private static String deckJson(String id) {
        return "{\"id\":\"" + id + "\",\"title\":\"Bench " + id + "\",\"slides\":["
                + "\"s1\",\"s2\",\"s3\",\"s4\",\"s5\",\"s6\"],\"modified\":\"2026-01-01T00:00:00Z\"}";
    }

    private static String slideSource(int n) {
        StringBuilder sb = new StringBuilder(4000);
        sb.append("// slide ").append(n).append('\n');
        for (int i = 0; i < 40; i++) {
            sb.append("export const s_").append(n).append('_').append(i)
              .append(" = <Slide bg=\"#0").append(i % 10).append("\">step ").append(i).append("</Slide>;\n");
        }
        return sb.toString();
    }

    private static void buildCorpus(Path root) throws Exception {
        // the big deck
        Path big = root.resolve("deck-big");
        Files.createDirectories(big.resolve("slides"));
        Files.createDirectories(big.resolve("assets"));
        Files.writeString(big.resolve("deck.json"), deckJson("deck-big"), StandardCharsets.UTF_8);
        for (int i = 0; i < SLIDES_IN_BIG; i++) {
            Files.writeString(big.resolve("slides/slide-" + i + ".tsx"), slideSource(i), StandardCharsets.UTF_8);
        }
        byte[] asset = new byte[ASSET_KB * 1024];
        for (int i = 0; i < ASSETS_IN_BIG; i++) {
            asset[0] = (byte) i;
            Files.write(big.resolve("assets/asset-" + i + ".png"), asset);
        }
        // plus a few normal decks
        for (int d = 0; d < DECKS - 1; d++) {
            Path deck = root.resolve(String.format(Locale.ROOT, "deck-%02d", d));
            Files.createDirectories(deck.resolve("slides"));
            String id = String.format(Locale.ROOT, "deck-%02d", d);
            Files.writeString(deck.resolve("deck.json"), deckJson(id), StandardCharsets.UTF_8);
            for (int i = 0; i < SMALL_DECK_SLIDES; i++) {
                Files.writeString(deck.resolve("slides/slide-" + i + ".tsx"), slideSource(i), StandardCharsets.UTF_8);
            }
        }
    }
}
