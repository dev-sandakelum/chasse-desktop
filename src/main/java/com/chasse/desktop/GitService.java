package com.chasse.desktop;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Git for desktop decks (Desktop Bridge Protocol v2 — the {@code git}
 * capability).
 *
 * <p>A deck is "connected" only when the deck folder ITSELF is a git
 * repository ({@code <deck>/.git}) checked out on {@code main}. Anything else
 * — no repository, a deck nested inside a bigger repository, another branch,
 * detached HEAD, git not installed — answers {@code {connected:false}} and the
 * web app shows no git button at all. Nothing here ever creates a repository.</p>
 *
 * <p>Two commands:</p>
 * <ul>
 *   <li>{@code git.status} → {@code {connected:true, branch:"main", dirty}} —
 *       {@code dirty} is true when the folder has modified or untracked files.</li>
 *   <li>{@code git.publish} → {@code git add -A} · {@code git commit -m
 *       "chasse: update <date time>"} (the message is generated here, the page
 *       cannot supply one) · {@code git push -u origin main}. Every git outcome
 *       comes back as {@code {ok, committed, commit?, error?}} so the page can
 *       explain it; only a missing deck or a broken bridge is an error.</li>
 * </ul>
 *
 * <p>{@code git} runs as a child process with a fixed argv (no shell) in the
 * deck folder resolved by {@link FsService} — the same confinement as every fs
 * command. Java starts Windows children with {@code CREATE_NO_WINDOW}, so no
 * console flashes, and every prompt is disabled (terminal prompts, the Windows
 * credential manager UI) so a missing login fails fast with a clear message
 * instead of hanging the request.</p>
 */
public final class GitService {

    /** git.publish on a deck that git.status reports as not connected. */
    public static final String CODE_NOT_CONNECTED = "NOT_CONNECTED";

    private static final long STATUS_TIMEOUT_MS = 15_000;
    private static final long PUSH_TIMEOUT_MS = 90_000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, String> ENV = Map.of(
            "GIT_TERMINAL_PROMPT", "0",
            "GCM_INTERACTIVE", "Never",
            "GIT_OPTIONAL_LOCKS", "0",
            "LC_ALL", "C");

    private static final Pattern NO_IDENTITY = Pattern.compile(
            "Please tell me who you are|unable to auto-detect email|user\\.(name|email)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_REMOTE = Pattern.compile(
            "does not appear to be a git repository|No such remote|No configured push destination", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH = Pattern.compile(
            "could not read (Username|Password)|Authentication failed|Permission denied \\(publickey\\)|403"
                    + "|terminal prompts disabled|Invalid username or (password|token)|HTTP Basic: Access denied"
                    + "|Repository not found", Pattern.CASE_INSENSITIVE);
    private static final Pattern REJECTED = Pattern.compile(
            "\\[rejected\\]|non-fast-forward|fetch first|Updates were rejected|failed to push some refs", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFLINE = Pattern.compile(
            "Could not resolve host|unable to access|Connection (refused|timed out)|Network is unreachable|ssh: connect to host",
            Pattern.CASE_INSENSITIVE);

    private final FsService fs;
    /** one publish at a time — two overlapping add/commit/push runs would race on the index */
    private final Object publishLock = new Object();

    public GitService(FsService fs) {
        this.fs = fs;
    }

    /** "chasse: update 2026-10-29 17:43:05" — local time, second precision. */
    static String commitMessageFor(LocalDateTime t) {
        return "chasse: update " + STAMP.format(t);
    }

    /* ------------------------------------------------------------------ */
    /* git.status                                                          */
    /* ------------------------------------------------------------------ */

    public JsonObject status(String deck) throws FsService.FsException {
        Path dir = fs.deckDir(deck);
        if (!Files.exists(dir.resolve(".git"))) {
            return notConnected("no-repo");
        }
        Run branch;
        Run changes;
        try {
            branch = run(dir, STATUS_TIMEOUT_MS, "symbolic-ref", "--short", "-q", "HEAD");
            if (branch.timedOut) {
                throw new FsService.FsException(FsService.CODE_IO_ERROR, "git did not answer in time");
            }
            if (branch.code != 0) {
                return notConnected("detached"); // exit 1 = HEAD is not a branch
            }
            String name = branch.out.trim();
            if (!"main".equals(name)) {
                JsonObject o = notConnected("branch");
                o.addProperty("branch", name);
                return o;
            }
            changes = run(dir, STATUS_TIMEOUT_MS, "status", "--porcelain", "--untracked-files=all");
        } catch (GitMissing e) {
            return notConnected("git-missing");
        } catch (IOException e) {
            throw new FsService.FsException(FsService.CODE_IO_ERROR, "git failed: " + e.getMessage());
        }
        if (changes.code != 0) {
            throw new FsService.FsException(FsService.CODE_IO_ERROR, "git status failed: " + lastLine(changes));
        }
        JsonObject o = new JsonObject();
        o.addProperty("connected", true);
        o.addProperty("branch", "main");
        o.addProperty("dirty", !changes.out.isBlank());
        return o;
    }

    private static JsonObject notConnected(String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("connected", false);
        o.addProperty("reason", reason);
        return o;
    }

    /* ------------------------------------------------------------------ */
    /* git.publish                                                         */
    /* ------------------------------------------------------------------ */

    public JsonObject publish(String deck) throws FsService.FsException {
        Path dir = fs.deckDir(deck);
        synchronized (publishLock) {
            JsonObject st = status(deck);
            if (!st.get("connected").getAsBoolean()) {
                throw new FsService.FsException(CODE_NOT_CONNECTED,
                        "This deck folder is not a git repository on the main branch");
            }
            boolean committed = false;
            String message = null;
            try {
                if (st.get("dirty").getAsBoolean()) {
                    // stage the whole deck folder — the folder IS the repository
                    Run add = run(dir, STATUS_TIMEOUT_MS, "add", "-A", "--", ".");
                    if (add.code != 0) {
                        return result(false, false, null, "git add failed: " + lastLine(add));
                    }
                    // exit 1 = something is staged (0 = every change was ignored)
                    Run staged = run(dir, STATUS_TIMEOUT_MS, "diff", "--cached", "--quiet");
                    if (staged.code == 1) {
                        message = commitMessageFor(LocalDateTime.now());
                        Run commit = run(dir, STATUS_TIMEOUT_MS, "commit", "-m", message);
                        if (commit.code != 0) {
                            String text = commit.err + "\n" + commit.out;
                            if (NO_IDENTITY.matcher(text).find()) {
                                return result(false, false, null, "Git needs an identity before it can commit. Run "
                                        + "git config --global user.name \"…\" and git config --global user.email \"…\" "
                                        + "once in a terminal, then try again.");
                            }
                            return result(false, false, null, "git commit failed: " + lastLine(commit));
                        }
                        committed = true;
                    }
                }
                // -u: the first push of a fresh repository also sets the upstream
                Run push = run(dir, PUSH_TIMEOUT_MS, "push", "-u", "origin", "main");
                if (push.code != 0) {
                    return result(false, committed, message, explainPush(push));
                }
                return result(true, committed, message, null);
            } catch (GitMissing e) {
                return result(false, committed, message, "git is not installed on this machine (or not on PATH).");
            } catch (IOException e) {
                throw new FsService.FsException(FsService.CODE_IO_ERROR, "git failed: " + e.getMessage());
            }
        }
    }

    private static JsonObject result(boolean ok, boolean committed, String commit, String error) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("committed", committed);
        if (commit != null) {
            o.addProperty("commit", commit);
        }
        if (error != null) {
            o.addProperty("error", error);
        }
        return o;
    }

    private static String explainPush(Run push) {
        String text = push.err + "\n" + push.out;
        if (push.timedOut) {
            return "Git did not answer in time. Check the network connection and try again.";
        }
        if (NO_REMOTE.matcher(text).find()) {
            return "This repository has no \"origin\" remote. Add one in a terminal (git remote add origin <url>), then push again.";
        }
        if (AUTH.matcher(text).find()) {
            return "The remote refused the credentials. Sign in to git once in a terminal (credential manager, token or SSH key), then push again.";
        }
        if (REJECTED.matcher(text).find()) {
            return "The remote has commits this folder does not have. Pull in a terminal, resolve anything that conflicts, then push again.";
        }
        if (OFFLINE.matcher(text).find()) {
            return "The remote could not be reached. Check the network connection and the remote URL.";
        }
        return "git push failed: " + lastLine(push);
    }

    /* ------------------------------------------------------------------ */
    /* process plumbing                                                    */
    /* ------------------------------------------------------------------ */

    /** One finished git run (stdout/stderr fully drained). */
    record Run(int code, String out, String err, boolean timedOut) {}

    /** {@code git} could not be started at all (not installed / not on PATH). */
    static final class GitMissing extends IOException {
        private static final long serialVersionUID = 1L;

        GitMissing(Throwable cause) {
            super("git is not installed (or not on PATH)", cause);
        }
    }

    /**
     * Runs {@code git <args>} in {@code dir}. Both pipes are drained on
     * daemon threads so a chatty command can never block on a full pipe; a
     * run that outlives {@code timeoutMs} is killed and reported as timed out
     * (its exit code is then meaningless and set to -1).
     */
    private static Run run(Path dir, long timeoutMs, String... args) throws IOException {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.environment().putAll(ENV);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new GitMissing(e);
        }
        p.getOutputStream().close(); // git never gets stdin — nothing can wait on input
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Thread outDrain = drain(p.getInputStream(), out, "git-stdout");
        Thread errDrain = drain(p.getErrorStream(), err, "git-stderr");
        boolean timedOut = false;
        try {
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                timedOut = true;
                p.destroyForcibly();
                p.waitFor(5, TimeUnit.SECONDS);
            }
            outDrain.join(2_000);
            errDrain.join(2_000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for git");
        }
        int code = timedOut ? -1 : p.exitValue();
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), timedOut);
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream into, String name) {
        Thread t = new Thread(() -> {
            try (InputStream s = in) {
                s.transferTo(into);
            } catch (IOException ignored) {
                // the process was killed — whatever was read is kept
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** The last non-blank line git printed (stderr first), for messages. */
    private static String lastLine(Run r) {
        for (String text : new String[]{r.err, r.out}) {
            String[] lines = text.split("\\r?\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                if (!lines[i].isBlank()) {
                    return lines[i].trim();
                }
            }
        }
        return r.timedOut ? "timed out" : "exit code " + r.code;
    }
}
