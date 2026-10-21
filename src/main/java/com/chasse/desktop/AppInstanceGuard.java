package com.chasse.desktop;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Single-instance guard for the desktop app.
 *
 * <p>Holds an exclusive OS-level {@link FileLock} on
 * {@code ~/.chasse-studio/app.lock} for the lifetime of the JVM. A second
 * launch fails to acquire the lock and exits with a friendly message.</p>
 *
 * <p>Why this matters now: the browser profile under
 * {@code ~/.chasse-studio/browser-profile} is a real Chromium profile that the
 * second instance must never touch — Chromium itself locks profile folders,
 * and two writers would corrupt IndexedDB/localStorage data. The lock also
 * prevents two LAN sync servers from fighting over port 8765.</p>
 *
 * <p>The lock is released by the OS when the process exits (even on crash);
 * no stale-lock recovery is needed.</p>
 */
public final class AppInstanceGuard {

    private static FileChannel channel;
    private static FileLock lock;

    private AppInstanceGuard() {
    }

    /**
     * Tries to become the one running instance.
     *
     * @return true when this process owns the app lock; false when another
     *         instance is already running.
     */
    public static boolean acquire() {
        if (lock != null) {
            return true; // already acquired in this JVM
        }
        try {
            Files.createDirectories(AppConfig.USER_DIR);
            Path lockFile = AppConfig.USER_DIR.resolve("app.lock");
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            return lock != null;
        } catch (IOException e) {
            // A guard must never break the app: if the lock cannot be taken
            // for an unexpected IO reason, let the app run (the sync server
            // still reports a clear port-in-use error to the page).
            System.err.println("[instance] could not acquire the app lock: " + e.getMessage());
            return true;
        }
    }

    /** Releases the lock explicitly (optional — the OS also does this on exit). */
    public static void release() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (IOException ignored) {
        } finally {
            lock = null;
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException ignored) {
            } finally {
                channel = null;
            }
        }
    }
}
