package com.chasse.desktop;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executors;

/**
 * The loopback-only asset server for the {@code fs} capability
 * (contract §7).
 *
 * <p>Serves deck files (images, fonts, media) and accepts uploads (PUT) on a
 * RANDOM free port bound to {@code 127.0.0.1} ONLY — physically unreachable
 * from the Wi-Fi/LAN (the phone remote's server is a separate listener on
 * 0.0.0.0 and shares nothing with this one).</p>
 *
 * <p>URL shape: {@code http://127.0.0.1:<port>/<session-token>/<deck>/<path...>}
 * — every path segment is percent-encoded, so names with spaces and
 * non-English characters round-trip correctly.</p>
 *
 * <p>Defense in depth on every request: (1) Host must be 127.0.0.1/localhost
 * with this exact port (stops DNS rebinding), (2) a per-session token that no
 * other page can know, (3) Origin, when present, must be the trusted app
 * origin (stops other web pages from using a leaked URL), (4) the path is
 * re-confined inside the chosen decks folder by {@link FsService} on the Java
 * side, and (5) file size limits are enforced while streaming.</p>
 */
public final class LoopbackFileServer {

    private final HttpServer server;
    private final FsService fs;
    private final String token;
    private final int port;
    private final String trustedOrigin;

    public LoopbackFileServer(FsService fs, AppConfig config) throws IOException {
        this.fs = fs;
        this.token = newToken();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        this.server.createContext("/", this::handleSafe);
        this.server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fs-file-server");
            t.setDaemon(true);
            return t;
        }));
        this.port = server.getAddress().getPort();
        this.trustedOrigin = originOf(config.appUrl());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return port;
    }

    /** Absolute URL for fs.fileUrl — percent-encodes every segment. */
    public String urlFor(String deck, String path) {
        StringBuilder sb = new StringBuilder("http://127.0.0.1:").append(port)
                .append('/').append(token)
                .append('/').append(pct(deck));
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            sb.append('/').append(pct(seg));
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* request handling                                                    */
    /* ------------------------------------------------------------------ */

    private void handleSafe(HttpExchange ex) {
        try {
            handle(ex);
        } catch (Exception e) {
            try {
                respondJson(ex, 500, "IO_ERROR", "internal error: " + e.getMessage());
            } catch (Exception ignored) {
                // socket already gone
            }
        } finally {
            try {
                ex.close();
            } catch (Exception ignored) {
                // always close
            }
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);

        // (1) Host — exact loopback host with our exact port
        String host = ex.getRequestHeaders().getFirst("Host");
        if (!hostAllowed(host)) {
            respondJson(ex, 403, "FORBIDDEN", "unexpected Host header");
            return;
        }

        // (2) Origin — when a browser sends one it must be the trusted app
        // origin (image/CSS subresource loads send none and rely on the token)
        String origin = ex.getRequestHeaders().getFirst("Origin");
        boolean cors = origin != null && !origin.isEmpty();
        if (cors && !originAllowed(origin)) {
            respondJson(ex, 403, "FORBIDDEN", "origin not allowed");
            return;
        }

        if ("OPTIONS".equals(method)) {
            handlePreflight(ex, origin);
            return;
        }

        // (3) token + path shape: /<token>/<deck>/<path...>
        String rawPath = ex.getRequestURI().getRawPath();
        String[] segs = rawPath.isEmpty() ? new String[0] : rawPath.substring(1).split("/");
        if (segs.length < 2 || !MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),
                segs[0].getBytes(StandardCharsets.US_ASCII))) {
            // wrong token → indistinguishable from "nothing here"
            respondJson(ex, 404, "NOT_FOUND", "not found");
            return;
        }
        String deck;
        String rel;
        try {
            deck = decodeSegment(segs[1]);
            StringBuilder rb = new StringBuilder();
            for (int i = 2; i < segs.length; i++) {
                if (segs[i].isEmpty()) {
                    continue;
                }
                if (rb.length() > 0) {
                    rb.append('/');
                }
                rb.append(decodeSegment(segs[i]));
            }
            rel = rb.toString();
        } catch (FsService.FsException e) {
            respondJson(ex, 400, e.code, e.getMessage());
            return;
        }
        if (rel.isEmpty()) {
            respondJson(ex, 404, "NOT_FOUND", "not found");
            return;
        }

        switch (method) {
            case "GET", "HEAD" -> serveFile(ex, cors ? origin : null, deck, rel, "HEAD".equals(method));
            case "PUT" -> receiveUpload(ex, cors ? origin : null, deck, rel);
            default -> {
                ex.getResponseHeaders().set("Allow", "GET, HEAD, PUT, OPTIONS");
                respondJson(ex, 405, "BAD_REQUEST", "method not allowed");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* GET / HEAD                                                          */
    /* ------------------------------------------------------------------ */

    private void serveFile(HttpExchange ex, String origin, String deck, String rel, boolean head)
            throws IOException {
        Path file;
        try {
            file = fs.resolveHttpReadTarget(deck, rel);
        } catch (FsService.FsException e) {
            respondJson(ex, statusOf(e.code), e.code, e.getMessage());
            return;
        }
        if (!Files.isRegularFile(file)) {
            respondJson(ex, 404, "NOT_FOUND", "file not found: " + rel);
            return;
        }
        long size;
        long mtime;
        try {
            size = Files.size(file);
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            respondJson(ex, 500, "IO_ERROR", "could not stat file: " + e.getMessage());
            return;
        }

        String etag = "W/\"" + size + "-" + mtime + "\"";
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        if (inm != null && inm.equals(etag)) {
            ex.getResponseHeaders().set("ETag", etag);
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=60");
            addCors(ex, origin);
            ex.sendResponseHeaders(304, -1);
            return;
        }

        long from = 0;
        long to = size - 1;
        int status = 200;
        String range = ex.getRequestHeaders().getFirst("Range");
        if (range != null && !range.isBlank()) {
            long[] r = parseRange(range, size);
            if (r == null) {
                ex.getResponseHeaders().set("Content-Range", "bytes */" + size);
                respondJson(ex, 416, "BAD_REQUEST", "unsatisfiable range: " + range);
                return;
            }
            from = r[0];
            to = r[1];
            status = 206;
        }
        long len = status == 206 ? (to - from + 1) : size;

        ex.getResponseHeaders().set("Content-Type", contentTypeOf(rel));
        ex.getResponseHeaders().set("Accept-Ranges", "bytes");
        ex.getResponseHeaders().set("ETag", etag);
        ex.getResponseHeaders().set("Last-Modified", httpDate(mtime));
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=60");
        if (status == 206) {
            ex.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + size);
        }
        addCors(ex, origin);
        if (head) {
            // the JDK server dislikes an explicit Content-Length on HEAD —
            // the length is already implied by Accept-Ranges/ETag clients
            ex.sendResponseHeaders(status, -1); // no body
            return;
        }
        ex.getResponseHeaders().set("Content-Length", String.valueOf(len));
        ex.sendResponseHeaders(status, len == 0 ? -1 : len);
        if (len == 0) {
            return;
        }
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = ex.getResponseBody()) {
            long skipped = 0;
            while (skipped < from) {
                long s = in.skip(from - skipped);
                if (s <= 0) {
                    break;
                }
                skipped += s;
            }
            byte[] buf = new byte[64 * 1024];
            long remaining = len;
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int got = in.read(buf, 0, want);
                if (got < 0) {
                    break; // file shrank mid-transfer — send what we have
                }
                out.write(buf, 0, got);
                remaining -= got;
            }
            out.flush();
        }
    }

    /** single-range parser: "bytes=a-b" | "bytes=a-" | "bytes=-suffix" */
    private static long[] parseRange(String header, long size) {
        String h = header.trim();
        if (!h.startsWith("bytes=") || h.contains(",") || size <= 0) {
            return null;
        }
        String spec = h.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String a = spec.substring(0, dash).trim();
        String b = spec.substring(dash + 1).trim();
        try {
            long from;
            long to;
            if (a.isEmpty()) {
                if (b.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(b);
                if (suffix <= 0) {
                    return null;
                }
                from = Math.max(0, size - suffix);
                to = size - 1;
            } else {
                from = Long.parseLong(a);
                to = b.isEmpty() ? size - 1 : Long.parseLong(b);
            }
            if (from < 0 || from >= size || to < from) {
                return null;
            }
            return new long[]{from, Math.min(to, size - 1)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* PUT (uploads)                                                       */
    /* ------------------------------------------------------------------ */

    private void receiveUpload(HttpExchange ex, String origin, String deck, String rel) throws IOException {
        Path target;
        try {
            target = fs.resolveHttpWriteTarget(deck, rel);
        } catch (FsService.FsException e) {
            respondJson(ex, statusOf(e.code), e.code, e.getMessage());
            return;
        }
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        long declared = -1;
        if (cl != null) {
            try {
                declared = Long.parseLong(cl.trim());
            } catch (NumberFormatException ignored) {
                // header lies — the streaming cap catches it
            }
        }
        if (decledTooLarge(declared)) {
            respondJson(ex, 413, "TOO_LARGE",
                    "file exceeds the upload limit of " + FsService.MAX_UPLOAD_BYTES + " bytes");
            return;
        }
        Path tmp = null;
        try {
            tmp = FsService.beginTempFile(target.getParent());
            long total = 0;
            try (InputStream in = ex.getRequestBody();
                 OutputStream out = java.nio.file.Files.newOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int got;
                while ((got = in.read(buf)) > 0) {
                    total += got;
                    if (total > FsService.MAX_UPLOAD_BYTES) {
                        throw new IOException("upload exceeds the limit of " + FsService.MAX_UPLOAD_BYTES + " bytes");
                    }
                    out.write(buf, 0, got);
                }
                out.flush();
                // fsync via the FileChannel below (cheap, single syscall)
                try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                        java.nio.file.StandardOpenOption.WRITE)) {
                    ch.force(true);
                }
            }
            FsService.finishTempFile(tmp, target);
            tmp = null; // moved — nothing to clean up
            fs.invalidateCache();
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("size", total);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            addCors(ex, origin);
            byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException e) {
            respondJson(ex, e.getMessage() != null && e.getMessage().contains("upload exceeds")
                    ? 413 : 500, e.getMessage() != null && e.getMessage().contains("upload exceeds")
                    ? "TOO_LARGE" : "IO_ERROR", e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static int statusOf(String code) {
        return switch (code) {
            case FsService.CODE_BAD_REQUEST, FsService.CODE_ALREADY_EXISTS -> 400;
            case FsService.CODE_NOT_FOUND -> 404;
            case FsService.CODE_TOO_LARGE -> 413;
            case FsService.CODE_NO_OS_PERMISSION, FsService.CODE_FORBIDDEN -> 403;
            case FsService.CODE_FOLDER_NOT_SET, FsService.CODE_FOLDER_MISSING -> 409;
            default -> 500;
        };
    }

    private static boolean decledTooLarge(long declared) {
        return declared > FsService.MAX_UPLOAD_BYTES;
    }

    /* ------------------------------------------------------------------ */
    /* CORS / preflight                                                    */
    /* ------------------------------------------------------------------ */

    private void handlePreflight(HttpExchange ex, String origin) throws IOException {
        // only for the real app origin
        if (origin == null || !originAllowed(origin)) {
            respondJson(ex, 403, "FORBIDDEN", "origin not allowed");
            return;
        }
        String acrh = ex.getRequestHeaders().getFirst("Access-Control-Request-Headers");
        String acrm = ex.getRequestHeaders().getFirst("Access-Control-Request-Method");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, HEAD, PUT, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers",
                acrh != null && !acrh.isBlank() ? acrh : "Content-Type");
        ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
        ex.getResponseHeaders().set("Vary", "Origin");
        // Chromium's Private Network Access: public https page → loopback
        if ("true".equalsIgnoreCase(ex.getRequestHeaders().getFirst("Access-Control-Request-Private-Network"))) {
            ex.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
        }
        // a method we never serve is still answered so the browser sees the
        // refusal from the actual request that follows
        if (acrm != null && !acrm.isBlank() && !"GET".equals(acrm) && !"HEAD".equals(acrm)
                && !"PUT".equals(acrm) && !"OPTIONS".equals(acrm)) {
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, HEAD, PUT, OPTIONS");
        }
        ex.sendResponseHeaders(204, -1);
    }

    private void addCors(HttpExchange ex, String origin) {
        if (origin != null && originAllowed(origin)) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().set("Vary", "Origin");
            ex.getResponseHeaders().set("Access-Control-Expose-Headers",
                    "Content-Length, Content-Range, Accept-Ranges, ETag, Last-Modified");
        }
    }

    /* ------------------------------------------------------------------ */
    /* gate helpers                                                        */
    /* ------------------------------------------------------------------ */

    private boolean hostAllowed(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        return ("127.0.0.1:" + port).equals(h) || ("localhost:" + port).equals(h);
    }

    private boolean originAllowed(String origin) {
        if (trustedOrigin == null || origin == null) {
            return false;
        }
        return trustedOrigin.equalsIgnoreCase(origin.trim());
    }

    /** scheme://host[:port] with default ports normalized; null when not http(s). */
    private static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            return scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* encoding / types                                                    */
    /* ------------------------------------------------------------------ */

    /** RFC 3986 percent-encoding of one path segment (unreserved chars stay). */
    static String pct(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append("0123456789ABCDEF".charAt(c >> 4));
                sb.append("0123456789ABCDEF".charAt(c & 0xf));
            }
        }
        return sb.toString();
    }

    /** Decodes %XX escapes ONLY ('+' stays '+', matching pct above). The
     *  escaped bytes are UTF-8, so multi-byte characters survive intact. */
    static String decodeSegment(String s) throws FsService.FsException {
        if (s.indexOf('%') < 0) {
            return checkDecoded(s);
        }
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new FsService.FsException(FsService.CODE_BAD_REQUEST, "bad percent-escape in URL");
                }
                int hi = hexVal(s.charAt(i + 1));
                int lo = hexVal(s.charAt(i + 2));
                if (hi < 0 || lo < 0) {
                    throw new FsService.FsException(FsService.CODE_BAD_REQUEST, "bad percent-escape in URL");
                }
                buf.write((hi << 4) | lo);
                i += 2;
            } else {
                // non-escaped non-ASCII is unusual (pct() encodes it) — funnel
                // it through UTF-8 bytes so it still decodes consistently
                byte[] cb = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                buf.write(cb, 0, cb.length);
            }
        }
        return checkDecoded(buf.toString(StandardCharsets.UTF_8));
    }

    private static String checkDecoded(String s) throws FsService.FsException {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x1f || c == 0x7f) {
                throw new FsService.FsException(FsService.CODE_BAD_REQUEST, "illegal character in URL segment");
            }
        }
        return s;
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private static String newToken() {
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) {
            sb.append("0123456789abcdef".charAt((b >> 4) & 0xf));
            sb.append("0123456789abcdef".charAt(b & 0xf));
        }
        return sb.toString();
    }

    private static String httpDate(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(millis));
    }

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("avif", "image/avif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("csv", "text/csv; charset=utf-8"),
            Map.entry("xml", "application/xml"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("wasm", "application/wasm"));

    static String contentTypeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            String type = MIME.get(ext);
            if (type != null) {
                return type;
            }
        }
        return "application/octet-stream";
    }

    /* ------------------------------------------------------------------ */
    /* responses                                                           */
    /* ------------------------------------------------------------------ */

    private static void respondJson(HttpExchange ex, int status, String code, String message) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("code", code);
        o.addProperty("message", message == null ? "" : message);
        byte[] body = o.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
