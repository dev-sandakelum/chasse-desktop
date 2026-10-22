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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Intercepts same-origin desktop asset URLs without opening a network port. */
public final class AssetRequestHandler extends CefRequestHandlerAdapter {
    private static final String PREFIX = "/__chasse_files__/";
    private static final ExecutorService IO = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "desktop-asset-io"); t.setDaemon(true); return t;
    });
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

    private static final class AssetResourceHandler extends CefResourceHandlerAdapter {
        private final FsService fs;
        private final AppConfig config;
        private final CefFrame frame;
        private final String initiator;
        private final String method;
        private final String url;
        private final String range;
        private final String ifNoneMatch;
        private final long started = System.nanoTime();
        private Path path;
        private FileChannel channel;
        private int status = 500;
        private String reason = "Internal Server Error";
        private String errorCode = "IO_ERROR";
        private String mime = "application/octet-stream";
        private long fileSize, modified, offset, remaining, bytesSent;
        private String etag = "";
        private boolean head;

        AssetResourceHandler(FsService fs, AppConfig config, CefFrame frame, String initiator, CefRequest request, URI requestUri) {
            this.fs = fs; this.config = config; this.frame = frame; this.initiator = initiator;
            this.method = request.getMethod().toUpperCase(Locale.ROOT); this.url = requestUri.toString();
            this.range = request.getHeaderByName("Range"); this.ifNoneMatch = request.getHeaderByName("If-None-Match");
        }

        @Override public boolean open(CefRequest request, BoolRef handleRequest, CefCallback callback) {
            handleRequest.set(false);
            IO.execute(() -> {
                try { prepare(); }
                catch (Exception e) {
                    String code = e instanceof FsService.FsException fe ? fe.code : "";
                    errorCode = code.isBlank() ? "IO_ERROR" : code;
                    status = FsService.CODE_NOT_FOUND.equals(code) ? 404
                            : (FsService.CODE_FORBIDDEN.equals(code) || FsService.CODE_BAD_REQUEST.equals(code) || FsService.CODE_NO_OS_PERMISSION.equals(code)) ? 403 : 500;
                    reason = status == 403 ? "Forbidden" : status == 404 ? "Not Found" : "Internal Server Error";
                    System.err.println("[asset-intercept] " + url + " status=" + status + " reason=" + e.getMessage());
                }
                callback.Continue();
            });
            return true;
        }

        private void prepare() throws Exception {
            URI u = uri(url);
            if (!trusted(u, frame, initiator, config)) { status = 403; errorCode = "FORBIDDEN"; reason = "Forbidden"; return; }
            if (!"GET".equals(method) && !"HEAD".equals(method)) { status = 405; errorCode = "BAD_REQUEST"; reason = "Method Not Allowed"; return; }
            String[] seg = u.getRawPath().substring(PREFIX.length()).split("/", -1);
            if (seg.length < 2) { status = 404; errorCode = "NOT_FOUND"; reason = "Not Found"; return; }
            String deck = decode(seg[0]);
            StringBuilder relative = new StringBuilder();
            for (int i = 1; i < seg.length; i++) {
                if (seg[i].isEmpty()) throw new FsService.FsException(FsService.CODE_BAD_REQUEST, "empty path segment");
                if (relative.length() > 0) relative.append('/');
                relative.append(decode(seg[i]));
            }
            path = fs.resolveHttpReadTarget(deck, relative.toString());
            if (!Files.isRegularFile(path)) { status = 404; errorCode = "NOT_FOUND"; reason = "Not Found"; return; }
            fileSize = Files.size(path); modified = Files.getLastModifiedTime(path).toMillis();
            etag = "W/\"" + fileSize + "-" + modified + "\"";
            mime = contentType(path.getFileName().toString());
            if (etag.equals(ifNoneMatch)) { status = 304; reason = "Not Modified"; return; }
            offset = 0; remaining = fileSize; status = 200; reason = "OK";
            if (range != null && !range.isBlank()) {
                long[] r = parseRange(range, fileSize);
                if (r == null) { status = 416; errorCode = "BAD_REQUEST"; reason = "Range Not Satisfiable"; remaining = 0; return; }
                offset = r[0]; remaining = r[1] - r[0] + 1; status = 206; reason = "Partial Content";
            }
            head = "HEAD".equals(method);
            if (!head) { channel = FileChannel.open(path, StandardOpenOption.READ); channel.position(offset); }
        }

        @Override public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
            response.setStatus(status); response.setStatusText(reason); response.setMimeType(mime);
            long len = status == 206 || status == 200 ? remaining : 0;
            responseLength.set((int)Math.min(Integer.MAX_VALUE, len));
            response.setHeaderByName("Content-Length", Long.toString(len), true);
            response.setHeaderByName("X-Content-Type-Options", "nosniff", true);
            if (status >= 400) response.setHeaderByName("X-Chasse-Error-Code", errorCode, true);
            response.setHeaderByName("Cache-Control", "no-cache", true);
            if (!etag.isEmpty()) response.setHeaderByName("ETag", etag, true);
            if (modified > 0) response.setHeaderByName("Last-Modified", httpDate(modified), true);
            response.setHeaderByName("Accept-Ranges", "bytes", true);
            if (status == 206) response.setHeaderByName("Content-Range", "bytes " + offset + "-" + (offset + remaining - 1) + "/" + fileSize, true);
            if (status == 416) response.setHeaderByName("Content-Range", "bytes */" + fileSize, true);
            if ((status != 200 && status != 206) || head) log();
        }

        @Override public boolean read(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefResourceReadCallback callback) {
            if (head || remaining <= 0 || channel == null) { bytesRead.set(0); log(); return false; }
            bytesRead.set(0);
            int count = (int)Math.min(Math.min(bytesToRead, dataOut.length), remaining);
            IO.execute(() -> {
                try {
                    int n = channel.read(ByteBuffer.wrap(dataOut, 0, count));
                    if (n < 0) n = 0;
                    remaining -= n; bytesSent += n;
                    callback.Continue(n);
                    if (n == 0 || remaining == 0) {
                        try { channel.close(); } catch (IOException ignored) {}
                        log();
                    }
                } catch (Exception e) {
                    System.err.println("[asset-intercept] read failed " + url + ": " + e.getMessage());
                    callback.Continue(-2); log();
                }
            });
            return true;
        }

        @Override public void cancel() { try { if (channel != null) channel.close(); } catch (IOException ignored) {} log(); }
        private void log() {
            if (logged) return; logged = true;
            System.out.println("[asset-intercept] " + url + " status=" + status + " bytes=" + bytesSent + " ms=" + ((System.nanoTime()-started)/1_000_000));
        }
        private boolean logged;
    }

    private static boolean trusted(URI request, CefFrame frame, String initiator, AppConfig config) {
        if (request == null || frame == null || !frame.isValid()) return false;
        URI frameUri = uri(frame.getURL());
        if (frameUri == null || !sameOrigin(request, frameUri) || !trustedOrigin(frameUri, config)) return false;
        if (initiator != null && !initiator.isBlank() && !"null".equals(initiator)) {
            URI i = uri(initiator); if (i == null || !sameOrigin(i, frameUri)) return false;
        }
        return true;
    }
    private static boolean trustedOrigin(URI u, AppConfig config) {
        URI app = uri(config.appUrl());
        return (app != null && sameOrigin(app, u)) || (("localhost".equalsIgnoreCase(u.getHost()) || "127.0.0.1".equals(u.getHost())) && u.getPort() == 3000);
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
            String s=h.substring(6).trim(); int d=s.indexOf('-'); if (d<0) return null;
            String a=s.substring(0,d), b=s.substring(d+1); long from,to;
            if (a.isEmpty()) { long suffix=Long.parseLong(b); if(suffix<=0)return null; from=Math.max(0,size-suffix);to=size-1; }
            else { from=Long.parseLong(a); to=b.isEmpty()?size-1:Math.min(size-1,Long.parseLong(b)); }
            return from<0 || from>=size || to<from ? null : new long[]{from,to};
        } catch(Exception e) { return null; }
    }
    private static String httpDate(long millis) {
        SimpleDateFormat f=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",Locale.US); f.setTimeZone(TimeZone.getTimeZone("GMT")); return f.format(new Date(millis));
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
            default -> "application/octet-stream";
        };
    }
}
