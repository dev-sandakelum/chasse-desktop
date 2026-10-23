package com.chasse.desktop;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Central worker-thread infrastructure for the whole desktop app.
 *
 * <p>Every pool here is created ONCE, has NAMED daemon threads, a BOUNDED
 * queue (or a bounded semaphore admission for the priority pool) and is shut
 * down cleanly by a JVM shutdown hook. Nothing in the app may create ad-hoc
 * thread pools any more — that was the source of both unbounded thread growth
 * (the old cached {@code fs-worker} could explode on chunked uploads) and
 * anonymous threads that were impossible to reason about in thread dumps.</p>
 *
 * <h2>Pools and their sizing rationale</h2>
 * <ul>
 *   <li><b>{@code chasse-io-N}</b> — small pool for parallel disk work that
 *       FsService fans out internally (deck scans, file copies, stats). Disk,
 *       antivirus and the OS page cache have limits; more threads just queue
 *       at the device. Sized {@code clamp(cores/2, 2, 6)}. The rejection
 *       policy is caller-runs, which makes {@link #parallelMap} deadlock-proof:
 *       a saturated pool degrades the caller to inline work instead of
 *       stalling it.</li>
 *   <li><b>{@code chasse-fs-N}</b> — serves {@code fs.*} bridge commands from
 *       the page. Bounded queue; when saturated the bridge answers
 *       {@code BUSY} instead of growing threads (back-pressure, never a
 *       blocked CEF UI thread).</li>
 *   <li><b>{@code chasse-asset-N}</b> — streams {@code /__chasse_files__/}
 *       bytes to CEF. Priority-ordered (see {@link #assetSubmit}) so the
 *       slide the user is looking at wins pool slots over background
 *       thumbnails, and continuation reads finish before new prepares so
 *       in-flight streams release their file handles instead of starving.
 *       Sized {@code clamp(cores, 4, 16)}: asset reads are overwhelmingly
 *       page-cache hits, so they parallelise better than raw disk.</li>
 *   <li><b>{@code chasse-bridge-N}</b> — small pool for v1 bridge actions so
 *       nothing potentially slow (server snapshots, room joins) runs on the
 *       CEF UI thread.</li>
 *   <li><b>{@code chasse-startup-N}</b> — one-shot pool for the parallel
 *       startup phase; shut down as soon as the UI is up.</li>
 *   <li><b>{@code chasse-scheduler}</b> — single-thread scheduled pool for
 *       TTL reapers (abandoned upload sessions) and other housekeeping.</li>
 * </ul>
 *
 * <h2>Correctness contract of {@link #parallelMap}</h2>
 * <ul>
 *   <li>Results are returned in INPUT order no matter the completion order
 *       (index-addressed slots).</li>
 *   <li>One failed task never stops the others — every item is attempted,
 *       failures are collected into the caller's error list and the slot
 *       stays {@code null}.</li>
 *   <li>Every task can be cancelled on its own via the per-call
 *       {@link CancelToken} (checked between items, never a shared global
 *       signal).</li>
 *   <li>If called from an {@code chasse-io} thread it degrades to sequential
 *       execution — a pool task can therefore never wait for the pool it is
 *       running on (no self-deadlock).</li>
 * </ul>
 */
public final class WorkerPools {

    private WorkerPools() {}

    /* ------------------------------------------------------------------ */
    /* per-task cancellation                                               */
    /* ------------------------------------------------------------------ */

    /** Independent cancel signal for one unit of work (never shared). */
    @FunctionalInterface
    public interface CancelToken {
        boolean isCancelled();
    }

    public static final CancelToken NEVER_CANCELLED = () -> false;

    /** A cancel token backed by its own volatile flag. */
    public static final class Flag implements CancelToken {
        private volatile boolean cancelled;
        public void cancel() { cancelled = true; }
        @Override public boolean isCancelled() { return cancelled; }
    }

    /* ------------------------------------------------------------------ */
    /* throwing function (checked exceptions without wrapping)             */
    /* ------------------------------------------------------------------ */

    @FunctionalInterface
    public interface ThrowingFunction<T, R> {
        R apply(T value) throws Exception;
    }

    /* ------------------------------------------------------------------ */
    /* pool sizing                                                         */
    /* ------------------------------------------------------------------ */

    private static final int CORES = Runtime.getRuntime().availableProcessors();

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    /** Parallel disk workers — measured: see about/decisions.md ("pool sizing"). */
    static final int IO_THREADS   = clamp(CORES / 2, 2, 6);
    /** CEF asset streaming threads. */
    static final int ASSET_THREADS = clamp(CORES, 4, 16);
    /** fs bridge command threads. */
    static final int FS_THREADS    = clamp(CORES / 2, 2, 6);
    /** v1 bridge action threads. */
    static final int BRIDGE_THREADS = 2;

    private static final int IO_QUEUE    = 512;
    private static final int FS_QUEUE    = 400;
    private static final int BRIDGE_QUEUE = 128;
    private static final int ASSET_QUEUE  = 2048;

    private static final AtomicInteger POOL_SEQ = new AtomicInteger();

    private static ThreadFactory namedDaemon(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /* ------------------------------------------------------------------ */
    /* the pools                                                           */
    /* ------------------------------------------------------------------ */

    /** Parallel disk work fanned out inside FsService operations. */
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
            IO_THREADS, IO_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(IO_QUEUE), namedDaemon("chasse-io"),
            // caller-runs keeps parallelMap deadlock-proof under saturation
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** fs.* bridge commands (file IO + native dialogs, never the CEF UI thread). */
    private static final ThreadPoolExecutor FS = new ThreadPoolExecutor(
            FS_THREADS, FS_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(FS_QUEUE), namedDaemon("chasse-fs"),
            // saturation is reported to the page as BUSY by the bridge
            new ThreadPoolExecutor.AbortPolicy());

    /** v1 bridge actions off the CEF UI thread. */
    private static final ThreadPoolExecutor BRIDGE = new ThreadPoolExecutor(
            BRIDGE_THREADS, BRIDGE_THREADS, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(BRIDGE_QUEUE), namedDaemon("chasse-bridge"),
            new ThreadPoolExecutor.AbortPolicy());

    /** One-shot parallel startup work (LAN scan, FsService config, icon decode). */
    private static final ThreadPoolExecutor STARTUP = new ThreadPoolExecutor(
            3, 3, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), namedDaemon("chasse-startup"),
            new ThreadPoolExecutor.CallerRunsPolicy());

    private static final ScheduledThreadPoolExecutor SCHED = new ScheduledThreadPoolExecutor(
            1, namedDaemon("chasse-scheduler"));
    static { SCHED.setRemoveOnCancelPolicy(true); }

    /* ------------------------------------------------------------------ */
    /* priority asset pool                                                 */
    /* ------------------------------------------------------------------ */

    /** Priority levels for the asset pool — LOWER runs first. */
    public static final int P_READ_CRITICAL  = 0; // continue a critical stream
    public static final int P_PREPARE_CRITICAL = 1; // open a critical asset
    public static final int P_READ           = 2; // continue any started stream
    public static final int P_PREPARE        = 3; // open a background asset

    /** A comparable, individually cancellable task for the asset pool. */
    private static final class PriorityTask implements Runnable, Comparable<PriorityTask> {
        final int priority;
        final long seq = SEQ.getAndIncrement();
        final BooleanSupplier cancelled;
        final Runnable body;
        PriorityTask(int priority, BooleanSupplier cancelled, Runnable body) {
            this.priority = priority;
            this.cancelled = cancelled == null ? () -> false : cancelled;
            this.body = body;
        }
        @Override public int compareTo(PriorityTask o) {
            int c = Integer.compare(priority, o.priority);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
        @Override public void run() {
            if (cancelled.getAsBoolean()) return; // per-task cancel, skip silently
            body.run();
        }
    }

    private static final AtomicLong SEQ = new AtomicLong();
    private static final Semaphore ASSET_PERMITS = new Semaphore(ASSET_QUEUE);

    private static final ThreadPoolExecutor ASSET = new ThreadPoolExecutor(
            ASSET_THREADS, ASSET_THREADS, 0L, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(), namedDaemon("chasse-asset"),
            new ThreadPoolExecutor.AbortPolicy());

    /**
     * Submits a prioritised, individually cancellable task to the asset pool.
     *
     * <p>The admission permit (the bound) is released automatically when the
     * task finishes or is skipped as cancelled — callers never manage it.</p>
     *
     * @return {@code true} if accepted; {@code false} when the bounded queue
     *         is saturated (the caller should fail the request fast — e.g. a
     *         503 — instead of blocking any thread).
     */
    public static boolean assetSubmit(int priority, BooleanSupplier cancelled, Runnable task) {
        if (!ASSET_PERMITS.tryAcquire()) return false;
        boolean accepted = false;
        try {
            Runnable guarded = () -> {
                try { task.run(); } finally { ASSET_PERMITS.release(); }
            };
            ASSET.execute(new PriorityTask(priority, cancelled, guarded));
            accepted = true;
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        } finally {
            if (!accepted) ASSET_PERMITS.release();
        }
    }

    /* ------------------------------------------------------------------ */
    /* accessors                                                           */
    /* ------------------------------------------------------------------ */

    /** Executor for v1 bridge actions. Throws RejectedExecutionException when saturated. */
    public static void bridgeExecute(Runnable task) { BRIDGE.execute(task); }

    /** Executor for fs.* commands. Throws RejectedExecutionException when saturated. */
    public static void fsExecute(Runnable task) { FS.execute(task); }

    /** One-shot startup pool. */
    public static <T> Future<T> startup(Callable<T> task) { return STARTUP.submit(task); }

    /** Housekeeping scheduler (TTL reapers etc.). */
    public static ScheduledFuture<?> scheduleAtFixedRate(long initialMs, long periodMs, Runnable task) {
        return SCHED.scheduleAtFixedRate(task, initialMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** Shuts the one-shot startup pool down (called once the UI is up). */
    public static void startupDone() {
        STARTUP.shutdown();
    }

    /* ------------------------------------------------------------------ */
    /* bounded parallel map (the fan-out primitive)                        */
    /* ------------------------------------------------------------------ */

    private static volatile boolean ioShutdown = false;

    /**
     * Runs {@code fn} over every item with at most {@code parallelism} items
     * in flight, on the IO pool.
     *
     * <p>Guarantees: input-order results; one failure never stops the other
     * tasks (throwables are collected in {@code errors}); per-task
     * cancellation between items; deadlock-proof (sequential fallback when
     * called from an IO-pool thread or when the pool rejects work).</p>
     *
     * @param errors  optional sink for per-item failures (never null-safe
     *                order — synchronised appends)
     */
    public static <T, R> List<R> parallelMap(List<T> items, int parallelism,
                                             CancelToken cancel, ThrowingFunction<T, R> fn,
                                             List<Throwable> errors) {
        int n = items.size();
        if (n == 0) return new ArrayList<>();
        @SuppressWarnings("unchecked")
        R[] out = (R[]) new Object[n];
        List<R> result = Arrays.asList(out);
        if (parallelism <= 1 || n == 1 || onIoThread() || ioShutdown) {
            for (int i = 0; i < n; i++) {
                if (cancel != null && cancel.isCancelled()) break;
                try { out[i] = fn.apply(items.get(i)); }
                catch (Throwable t) { if (errors != null) record(errors, t); }
            }
            return result;
        }
        int workers = Math.min(parallelism, n);
        AtomicInteger cursor = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(workers);
        for (int w = 0; w < workers; w++) {
            try {
                IO.execute(() -> {
                    try {
                        runWorker(items, fn, cancel, errors, out, cursor);
                    } finally {
                        done.countDown();
                    }
                });
            } catch (RejectedExecutionException callerRuns) {
                // queue full (should not happen with caller-runs, but be safe):
                // degrade this worker to the calling thread
                runWorker(items, fn, cancel, errors, out, cursor);
                done.countDown();
            }
        }
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    private static <T, R> void runWorker(List<T> items, ThrowingFunction<T, R> fn,
                                         CancelToken cancel, List<Throwable> errors,
                                         R[] out, AtomicInteger cursor) {
        int n = items.size();
        int i;
        while ((cancel == null || !cancel.isCancelled()) && (i = cursor.getAndIncrement()) < n) {
            try {
                out[i] = fn.apply(items.get(i));
            } catch (Throwable t) {
                if (errors != null) record(errors, t);
            }
        }
    }

    private static void record(List<Throwable> errors, Throwable t) {
        synchronized (errors) { errors.add(t); }
    }

    /** True when the current thread belongs to the IO pool (self-deadlock guard). */
    static boolean onIoThread() {
        String name = Thread.currentThread().getName();
        return name != null && name.startsWith("chasse-io-");
    }

    /* ------------------------------------------------------------------ */
    /* clean shutdown                                                      */
    /* ------------------------------------------------------------------ */

    private static final AtomicInteger HOOK = new AtomicInteger();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(WorkerPools::shutdownAll, "chasse-pools-shutdown"));
    }

    /** Drains every pool; waits briefly so in-flight writes finish. */
    public static void shutdownAll() {
        if (HOOK.incrementAndGet() != 1) return;
        ioShutdown = true;
        List<ExecutorService> all = List.of(IO, FS, BRIDGE, STARTUP, ASSET, SCHED);
        for (ExecutorService pool : all) {
            try { pool.shutdown(); } catch (Exception ignored) {}
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        for (ExecutorService pool : all) {
            long wait = Math.max(1, deadline - System.nanoTime());
            try {
                if (!pool.awaitTermination(wait, TimeUnit.NANOSECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {}
        }
    }
}
