package com.chasse.desktop;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.callback.CefResourceReadCallback;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intercepts same-origin desktop asset URLs without opening a network port.
 *
 * <p>Audit notes (and the fixes that came out of it):</p>
 * <ul>
 *   <li><b>Trust check hardened.</b> JCEF frequently reports an EMPTY frame
 *       URL for subframe-initiated requests (the sandboxed stage runtime is an
 *       iframe), which used to fail "same origin" and 403 every asset the
 *       slides asked for. The frame check is now a best-effort signal, not a
 *       gate: a request is served when its own URL origin is the trusted app
 *       origin (or the dev server) — the /__chasse_files__/ URL space only
 *       exists inside the app, and FsService confines every path to a deck
 *       folder anyway. The initiator header, when present and parseable, must
 *       still match. Every 403 logs the exact reason.</li>
 *   <li><b>MIME table extended</b> — media (mp4/webm/mp3/wav/ogg), json, mjs,
 *       txt and md used to fall through to application/octet-stream, which
 *       made &lt;video&gt;/&lt;audio&gt; elements and fetch(json) fail inside the app.</li>
 *   <li><b>Range handling</b> — a multi-range or otherwise unsupported Range
 *       header now serves the full 200 body (RFC 9110 allows ignoring it)
 *       instead of failing with 416.</li>
 *   <li><b>If-Modified-Since</b> is honoured in addition to ETag.</li>
 *   <li><b>304 responses no longer carry Content-Length</b> (a 304 must not
 *       describe a body).</li>
 * </ul>
 *
 * <p><b>Streaming model (parallel loading):</b> every {@code open()} and every
 * {@code read()} chunk runs as a task on the shared bounded priority pool
 * ({@link WorkerPools#assetSubmit}) — the CEF UI thread never blocks and many
 * requests stream at the same time. Priorities:</p>
 * <ul>
 *   <li>The web app can mark an asset URL as user-critical with the query
 *       {@code ?ss_p=hi} (the Present start slide and its neighbours do this):
 *       those prepares and reads jump ahead of background work.</li>
 *   <li>Continuation reads of an ALREADY-OPENED stream outrank new prepares,
 *       so started streams finish and release their file handles instead of
 *       starving behind an endless queue of new opens.</li>
 * </ul>
 * <p>Cross-thread state written by {@code prepare()} on a pool thread and read
 * by {@code getResponseHeaders()}/{@code read()} on the CEF thread is passed
 * through an immutable {@link Prepared} snapshot (the old code relied on the
 * JNI Continue() boundary, which is not a Java Memory Model happens-before
 * edge). Each request owns an independent cancel flag: {@code cancel()} flips
 * it and closes the channel; queued tasks for that request see the flag and
 * skip their work instead of touching a dead handler.</p>
 */
public final class AssetRequestHandler extends CefRequestHandlerAdapter {
    private static final String PREFIX = "/__chasse_files__/";
    private final FsService fs;
    private final AppConfig config;

    public AssetRequestHandler(FsService fs, AppConfig config) { this.fs = fs; this.config = config; }

    @Override public CefResourceRequestHandler getResourceRequestHandler(
            CefBrowser browser, CefFrame frame, CefRequest request, boolean navigation,
            boolean download, String initiator, BoolRef disableDefaultHandling) {
        return new ResourceRequestHandler(frame, initiator);
    }

    private final class ResourceRequestHandler extends CefResourceRequestHandlerAdapter {
        private final CefFrame frame;
        private final String initiator;
        ResourceRequestHandler(CefFrame frame, String initiator) {
            this.frame = frame; this.initiator = initiator;
        }
        @Override public CefResourceHandler getResourceHandler(CefBrowser browser, CefFrame ignored, CefRequest request) {
            URI uri = uri(request.getURL());
            if (uri == null || uri.getRawPath() == null || !uri.getRawPath().startsWith(PREFIX)) return null;
            return new AssetResourceHandler(fs, config, frame, initiator, request, uri);
        }
    }

    /** Immutable response state — published once by prepare(), read everywhere. */
    private static final class Prepared {
        static final Prepared INTERNAL_ERROR = new Prepared(500, "Internal Server Error", "IO_ERROR",
                "application/octet-stream", 0, 0, 0, 0, "", false, null);
        static final Prepared BUSY = new Prepared(503, "Service Unavailable", "BUSY",
                "application/octet-stream", 0, 0, 0, 0, "", false, null);
        static final Prepared CANCELLED = new Prepared(499, "Client Closed Request", "CANCELLED",
                "application/octet-stream", 0, 0, 0, 0, "", false, null);

        final int status;
        final String reason;
        final String errorCode;
        final String mime;
        final long fileSize, modified, offset, remaining;
        final String etag;
        final boolean head;
        final FileChannel channel; // null unless a body will be streamed

        Prepared(int status, String reason, String errorCode, String mime,
                 long fileSize, long modified, long offset, long remaining,
                 String etag, boolean head, FileChannel channel) {
            this.status = status; this.reason = reason; this.errorCode = errorCode; this.mime = mime;
            this.fileSize = fileSize; this.modified = modified; this.offset = offset;
            this.remaining = remaining; this.etag = etag; this.head = head; this.channel = channel;
        }
    }

    private static final class AssetResourceHandler extends CefResourceHandlerAdapter {
        private final FsService fs;
        private final AppConfig config;
        private final CefFrame frame;
        private final String initiator;
        private final String method;
        private final String url;
        private final String range;
        private final String ifNoneMatch;
        private final String ifModifiedSince;
        private final boolean critical;      // ?ss_p=hi — user-visible asset
        private final long started = System.nanoTime();

        /** Per-request cancel signal — never shared with another request. */
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** Response snapshot — volatile so the CEF thread sees prepare()'s writes. */
        private volatile Prepared prepared = Prepared.INTERNAL_ERROR;
        /** Bytes still to send (mutated by pool threads between CEF reads). */
        private final AtomicLong remaining = new AtomicLong(0);
        private volatile boolean logged = false;

        AssetResourceHandler(FsService fs, AppConfig config, CefFrame frame, String initiator, CefRequest request, URI requestUri) {
            this.fs = fs; this.config = config; this.frame = frame; this.initiator = initiator;
            this.method = request.getMethod().toUpperCase(Locale.ROOT); this.url = requestUri.toString();
            this.range = request.getHeaderByName("Range"); this.ifNoneMatch = request.getHeaderByName("If-None-Match");
            this.ifModifiedSince = request.getHeaderByName("If-Modified-Since");
            this.critical = isCritical(requestUri.getRawQuery());
        }

        /** The web app marks Present-critical assets with ?ss_p=hi. */
        private static boolean isCritical(String rawQuery) {
            if (rawQuery == null) return false;
            for (String pair : rawQuery.split("&")) {
                if ("ss_p=hi".equals(pair)) return true;
            }
            return false;
        }

        @Override public boolean open(CefRequest request, BoolRef handleRequest, CefCallback callback) {
            handleRequest.set(false);
            boolean accepted = WorkerPools.assetSubmit(
                    critical ? WorkerPools.P_PREPARE_CRITICAL : WorkerPools.P_PREPARE,
                    cancelled::get,
                    () -> {
                        try {
                            prepare();
                        } catch (Exception e) {
                            String code = e instanceof FsService.FsException fe ? fe.code : "";
                            String errorCode = code.isBlank() ? "IO_ERROR" : code;
                            int status = FsService.CODE_NOT_FOUND.equals(code) ? 404
                                    : (FsService.CODE_FORBIDDEN.equals(code) || FsService.CODE_BAD_REQUEST.equals(code) || FsService.CODE_NO_OS_PERMISSION.equals(code)) ? 403 : 500;
                            String reason = status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Internal Server Error";
                            prepared = new Prepared(status, reason, errorCode, "application/octet-stream",
                                    0, 0, 0, 0, "", false, null);
                            System.err.println("[asset-intercept] " + url + " status=" + status + " reason=" + e.getMessage());
                        }
                        if (!cancelled.get()) {
                            callback.Continue();
                        }
                    });
            if (!accepted) {
                // bounded pool saturated — fail THIS request fast instead of
                // blocking or unbounding the app; CEF retries image loads on
                // its own and the page heals via the runtime's retry logic
                prepared = Prepared.BUSY;
                System.err.println("[asset-intercept] 503 (pool saturated) " + url);
            }
            return true;
        }

        private void prepare() throws Exception {
            URI u = uri(url);
            if (!trusted(u, frame, initiator, config)) {
                System.err.println("[asset-intercept] 403 " + url
                        + " — frame=" + safeFrameUrl(frame) + " initiator=" + initiator);
                prepared = error(403, "Forbidden", "FORBIDDEN"); return;
            }
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                prepared = error(405, "Method Not Allowed", "BAD_REQUEST"); return;
            }
            String[] seg = u.getRawPath().substring(PREFIX.length()).split("/", -1);
            if (seg.length < 2) { prepared = error(404, "Not Found", "NOT_FOUND"); return; }
            String deck = decode(seg[0]);
            StringBuilder relative = new StringBuilder();
            for (int i = 1; i < seg.length; i++) {
                if (seg[i].isEmpty()) throw new FsService.FsException(FsService.CODE_BAD_REQUEST, "empty path segment");
                if (relative.length() > 0) relative.append('/');
                relative.append(decode(seg[i]));
            }
            Path path = fs.resolveHttpReadTarget(deck, relative.toString());
            if (!Files.isRegularFile(path)) { prepared = error(404, "Not Found", "NOT_FOUND"); return; }
            long fileSize = Files.size(path);
            long modified = Files.getLastModifiedTime(path).toMillis();
            String etag = "W/\"" + fileSize + "-" + modified + "\"";
            String mime = contentType(path.getFileName().toString());
            if (etag.equals(ifNoneMatch) || (ifModifiedSince != null && !ifModifiedSince.isBlank()
                    && modified <= parseHttpDate(ifModifiedSince))) {
                prepared = new Prepared(304, "Not Modified", "", mime, fileSize, modified, 0, 0, etag, false, null);
                return;
            }
            long offset = 0, remainingBytes = fileSize;
            int status = 200; String reason = "OK";
            if (range != null && !range.isBlank()) {
                long[] r = parseRange(range, fileSize);
                if (r == null) {
                    // unsupported range syntax (multi-range, malformed) — serve
                    // the FULL body (RFC 9110 allows ignoring the header)
                    offset = 0; remainingBytes = fileSize; status = 200; reason = "OK";
                } else {
                    offset = r[0]; remainingBytes = r[1] - r[0] + 1; status = 206; reason = "Partial Content";
                }
            }
            boolean head = "HEAD".equals(method);
            FileChannel channel = null;
            if (!head && status != 304) {
                channel = FileChannel.open(path, StandardOpenOption.READ);
                channel.position(offset);
            }
            remaining.set(remainingBytes);
            prepared = new Prepared(status, reason, "", mime, fileSize, modified, offset, remainingBytes,
                    etag, head, channel);
        }

        private static Prepared error(int status, String reason, String code) {
            return new Prepared(status, reason, code, "application/octet-stream", 0, 0, 0, 0, "", false, null);
        }

        @Override public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            Prepared p = prepared;
            response.setStatus(p.status); response.setStatusText(p.reason); response.setMimeType(p.mime);
            long len = p.status == 206 || p.status == 200 ? p.remaining : 0;
            responseLength.set((int)Math.min(Integer.MAX_VALUE, len));
            if (p.status != 304) response.setHeaderByName("Content-Length", Long.toString(len), true);
            response.setHeaderByName("X-Content-Type-Options", "nosniff", true);
            if (p.status >= 400) response.setHeaderByName("X-Chasse-Error-Code", p.errorCode, true);
            response.setHeaderByName("Cache-Control", "no-cache", true);
            if (!p.etag.isEmpty() && p.status != 304) response.setHeaderByName("ETag", p.etag, true);
            if (p.modified > 0 && p.status != 304) response.setHeaderByName("Last-Modified", httpDate(p.modified), true);
            response.setHeaderByName("Accept-Ranges", "bytes", true);
            if (p.status == 206) response.setHeaderByName("Content-Range", "bytes " + p.offset + "-" + (p.offset + p.remaining - 1) + "/" + p.fileSize, true);
            if (p.status == 416) response.setHeaderByName("Content-Range", "bytes */" + p.fileSize, true);
            if ((p.status != 200 && p.status != 206) || p.head) log();
        }

        @Override public boolean read(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefResourceReadCallback callback) {
            Prepared p = prepared;
            if (p.head || p.channel == null || remaining.get() <= 0) { bytesRead.set(0); log(); return false; }
            bytesRead.set(0);
            int count = (int)Math.min(Math.min(bytesToRead, dataOut.length), remaining.get());
            FileChannel channel = p.channel;
            // Continuation reads of an already-opened stream outrank new
            // prepares (finish started work, release file handles, keep the
            // number of in-flight large files bounded).
            boolean accepted = WorkerPools.assetSubmit(
                    critical ? WorkerPools.P_READ_CRITICAL : WorkerPools.P_READ,
                    cancelled::get,
                    () -> {
                        try {
                            if (cancelled.get() || remaining.get() <= 0) {
                                return; // request died — do not touch the handler
                            }
                            int n = channel.read(ByteBuffer.wrap(dataOut, 0, count));
                            if (n < 0) n = 0;
                            remaining.addAndGet(-n);
                            if (!cancelled.get()) {
                                callback.Continue(n);
                            }
                            if (n == 0 || remaining.get() == 0) {
                                closeChannel(channel);
                                log();
                            }
                        } catch (Exception e) {
                            if (!cancelled.get()) {
                                System.err.println("[asset-intercept] read failed " + url + ": " + e.getMessage());
                                callback.Continue(-2);
                            }
                            closeChannel(channel);
                            log();
                        }
                    });
            if (!accepted) {
                // saturated: report a read failure for THIS request only
                closeChannel(channel);
                callback.Continue(-2);
                log();
            }
            return true;
        }

        private void closeChannel(FileChannel channel) {
            try { channel.close(); } catch (IOException ignored) {}
        }

        @Override public void cancel() {
            // Per-request cancellation: flip this request's OWN flag, then close
            // its channel. Queued pool tasks observe the flag and skip work.
            cancelled.set(true);
            Prepared p = prepared;
            if (p != null && p.channel != null) closeChannel(p.channel);
            log();
        }

        private void log() {
            if (logged) return;
            logged = true;
            Prepared p = prepared;
            long sent = p == null ? 0 : Math.max(0, p.remaining - remaining.get());
            System.out.println("[asset-intercept] " + url + " status=" + (p == null ? 500 : p.status)
                    + " bytes=" + sent + " ms=" + ((System.nanoTime()-started)/1_000_000)
                    + (cancelled.get() ? " (cancelled)" : ""));
        }
    }

    /**
     * Trust: the /__chasse_files__/ URL space exists only on the app's own
     * origin, so the REQUEST's own origin is the primary signal (JCEF often
     * hands us an empty/unknown frame URL for subframe requests — the sandboxed
     * stage runtime — which used to 403 every slide asset). The initiator, when
     * present and parseable, must still be the trusted origin.
     */
    private static boolean trusted(URI request, CefFrame frame, String initiator, AppConfig config) {
        if (request == null) return false;
        if (!trustedOrigin(request, config)) return false;
        // initiator, when usable, must be the trusted origin too
        if (initiator != null && !initiator.isBlank() && !"null".equals(initiator)) {
            URI i = uri(initiator);
            // an opaque ("null") origin initiator belongs to our own sandboxed
            // runtime frames — allowed; anything else must be the app origin
            if (i != null && i.getHost() != null && !trustedOrigin(i, config)) return false;
        }
        return true;
    }

    private static String safeFrameUrl(CefFrame frame) {
        try { return frame == null ? "none" : frame.getURL(); } catch (Exception e) { return "error"; }
    }

    private static boolean trustedOrigin(URI u, AppConfig config) {
        if (u == null || u.getHost() == null) return false;
        URI app = uri(config.appUrl());
        if (app != null && sameOrigin(app, u)) return true;
        return ("localhost".equalsIgnoreCase(u.getHost()) || "127.0.0.1".equals(u.getHost())) && u.getPort() == 3000;
    }
    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }
    private static int port(URI u) { return u.getPort() >= 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80); }
    private static URI uri(String value) { try { return URI.create(value); } catch (Exception e) { return null; } }
    private static String decode(String segment) throws FsService.FsException {
        try { return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (Exception e) { throw new FsService.FsException(FsService.CODE_BAD_REQUEST, "invalid URL encoding"); }
    }
    private static long[] parseRange(String h, long size) {
        if (!h.startsWith("bytes=") || h.contains(",") || size <= 0) return null;
        try {
            String s=h.substring(6).trim(); int d=s.indexOf('-'); if(d<0) return null;
            String a=s.substring(0,d), b=s.substring(d+1); long from,to;
            if (a.isEmpty()) { long suffix=Long.parseLong(b); if(suffix<=0)return null; from=Math.max(0,size-suffix);to=size-1; }
            else { from=Long.parseLong(a); to=b.isEmpty()?size-1:Math.min(size-1,Long.parseLong(b)); }
            return from<0 || from>=size || to<from ? null : new long[]{from,to};
        } catch(Exception e) { return null; }
    }
    private static String httpDate(long millis) {
        SimpleDateFormat f=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US); f.setTimeZone(TimeZone.getTimeZone("GMT")); return f.format(new Date(millis));
    }
    private static long parseHttpDate(String value) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT"));
            return f.parse(value.trim()).getTime();
        } catch (Exception e) { return Long.MAX_VALUE; }
    }
    private static String contentType(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png"; case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp"; case "gif" -> "image/gif";
            case "svg" -> "image/svg+xml"; case "avif" -> "image/avif";
            case "bmp" -> "image/bmp"; case "ico" -> "image/x-icon";
            case "css" -> "text/css; charset=utf-8"; case "woff" -> "font/woff";
            case "woff2" -> "font/woff2"; case "ttf" -> "font/ttf"; case "otf" -> "font/otf";
            case "mp4" -> "video/mp4"; case "webm" -> "video/webm";
            case "mp3" -> "audio/mpeg"; case "wav" -> "audio/wav"; case "ogg" -> "audio/ogg";
            case "json" -> "application/json; charset=utf-8";
            case "mjs" -> "text/javascript; charset=utf-8";
            case "txt", "md" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }
}
