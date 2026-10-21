package com.chasse.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The JCEF JavaScript bridge (Transport A of the Desktop Bridge Protocol).
 *
 * <p>Two request protocols travel over the SAME CefMessageRouter:</p>
 * <ul>
 *   <li>v1 (sync): request {@code {id, action, payload}}, response
 *       {@code {"id":..,"ok":true,"data":{..}}} or
 *       {@code {"id":..,"ok":false,"error":".."}}</li>
 *   <li>v2 (hello + fs, the "fs" capability): request {@code {cmd, ...params}}
 *       answered with the raw contract shapes — e.g.
 *       {@code {cmd:"hello"}} → {@code {app, version, capabilities:["sync","fs"]}},
 *       {@code fs.listDecks} → {@code [{id,title,folder,slides,modified}]}, and
 *       every failure as {@code {code, message}} with codes like
 *       FOLDER_NOT_SET / FOLDER_MISSING / NO_OS_PERMISSION / NOT_FOUND /
 *       TOO_LARGE / IO_ERROR. The web app falls back to browser storage when
 *       hello does not advertise {@code "fs"}.</li>
 * </ul>
 *
 * <p>Java → web page via {@code browser.executeJavaScript(
 * "window.__chasseDesktop && window.__chasseDesktop._handleEvent(<json>)", currentUrl, 0)}
 * — only while the current page origin matches the configured app origin.</p>
 *
 * <p>Trust model: only the configured app origin may use any action other than
 * {@code getBridgeInfo}, and only the trusted app origin may use the fs
 * commands (hello itself is capability discovery and answers any page —
 * the same information level as getBridgeInfo). Untrusted pages also never
 * receive pushed events.</p>
 */
public final class DesktopBridge {

    public static final String VERSION = "1.3.0";

    private final AppConfig config;
    private final ServerManager servers;
    private final SyncRooms rooms;
    private final FsService fs;
    private final LoopbackFileServer fileServer;
    private final Gson gson = new Gson();
    /** Worker for server lifecycle calls (start/stop/restart block). */
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bridge-worker");
        t.setDaemon(true);
        return t;
    });
    /** Worker for fs commands — file IO must never block the CEF UI thread,
     *  and fs.chooseRoot shows a native dialog for as long as the user wants. */
    private final ExecutorService fsWorker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fs-worker");
        t.setDaemon(true);
        return t;
    });

    private volatile CefBrowser browser;
    private volatile String currentUrl = "";
    /** native download support (set by Main) — serves the openDownloadFolder action */
    private volatile DownloadBridge downloads;

    public DesktopBridge(org.cef.CefClient client, AppConfig config, ServerManager servers, SyncRooms rooms) {
        this(client, config, servers, rooms, null, null);
    }

    public DesktopBridge(org.cef.CefClient client, AppConfig config, ServerManager servers, SyncRooms rooms,
                         FsService fs, LoopbackFileServer fileServer) {
        this.config = config;
        this.servers = servers;
        this.rooms = rooms;
        this.fs = fs;
        this.fileServer = fileServer;

        CefMessageRouter router = CefMessageRouter.create(); // default: window.cefQuery
        router.addHandler(new QueryHandler(), false);
        client.addMessageRouter(router);

        servers.addListener(new ServerListener());
        rooms.addListener(new RoomsListener());
    }

    /** Current page URL (kept in sync by Main's display handler). */
    public void setCurrentUrl(String url) {
        this.currentUrl = url == null ? "" : url;
    }

    /** The browser instance becomes available after creation in Main. */
    public void setBrowser(CefBrowser browser) {
        this.browser = browser;
    }

    /** The native download support (for the openDownloadFolder action). */
    public void setDownloads(DownloadBridge downloads) {
        this.downloads = downloads;
    }

    /* ------------------------------------------------------------------ */
    /* Origin trust                                                        */
    /* ------------------------------------------------------------------ */

    /** Origin (scheme://host[:port], default ports normalized) or null. */
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
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port == -1) {
                port = "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
            }
            return port == -1 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    /** True only when the current page origin equals the configured app origin. */
    public boolean isTrusted() {
        String appOrigin = originOf(config.appUrl());
        if (appOrigin == null) {
            return false; // blank app.url → never trusted
        }
        return appOrigin.equals(originOf(currentUrl));
    }

    /* ------------------------------------------------------------------ */
    /* Query handling                                                      */
    /* ------------------------------------------------------------------ */

    private final class QueryHandler extends CefMessageRouterHandlerAdapter {
        @Override
        public boolean onQuery(CefBrowser b, CefFrame frame, long queryId, String request,
                               boolean persistent, CefQueryCallback callback) {
            try {
                handleQuery(request, callback);
            } catch (Exception e) {
                respondError(callback, JsonNull.INSTANCE, "internal error");
            }
            return true; // always answer — never leave a query hanging
        }
    }

    private void handleQuery(String request, CefQueryCallback callback) {
        JsonElement id = JsonNull.INSTANCE;
        try {
            JsonElement parsed = JsonParser.parseString(request);
            if (!parsed.isJsonObject()) {
                respondError(callback, id, "bad request");
                return;
            }
            JsonObject req = parsed.getAsJsonObject();
            // protocol v2: {cmd, ...params} → raw contract responses (hello, fs.*)
            String cmd = optString(req, "cmd");
            if (cmd != null) {
                handleCmd(cmd, req, callback);
                return;
            }
            JsonElement idEl = req.get("id");
            if (idEl != null && !idEl.isJsonNull()) {
                id = idEl;
            }
            String action = optString(req, "action");
            JsonObject payload = new JsonObject();
            JsonElement payloadEl = req.get("payload");
            if (payloadEl != null && payloadEl.isJsonObject()) {
                payload = payloadEl.getAsJsonObject();
            }
            dispatch(action, payload, id, callback);
        } catch (Exception e) {
            respondError(callback, id, "bad request");
        }
    }

    private void dispatch(String action, JsonObject payload, JsonElement id, CefQueryCallback callback) {
        // getBridgeInfo is the only action available to untrusted pages
        if ("getBridgeInfo".equals(action)) {
            JsonObject data = new JsonObject();
            data.addProperty("trusted", isTrusted());
            data.addProperty("version", VERSION);
            data.addProperty("platform", System.getProperty("os.name", "unknown"));
            data.addProperty("appUrl", config.appUrl());
            respondOk(callback, id, data);
            return;
        }
        if (!isTrusted()) {
            respondError(callback, id, "not trusted");
            return;
        }
        if (action == null) {
            respondError(callback, id, "unknown action");
            return;
        }
        switch (action) {
            case "getServerStatus" -> respondOk(callback, id, servers.statusSnapshot());
            case "getServerLog" -> respondOk(callback, id, servers.logSnapshot());
            case "startServer" -> {
                System.out.println("[bridge] startServer received from page");
                worker.execute(servers::start);
                respondOkSimple(callback, id);
            }
            case "stopServer" -> {
                System.out.println("[bridge] stopServer received from page");
                worker.execute(servers::stop);
                respondOkSimple(callback, id);
            }
            case "restartServer" -> {
                System.out.println("[bridge] restartServer received from page");
                worker.execute(servers::restart);
                respondOkSimple(callback, id);
            }
            case "selectAddress" -> {
                servers.selectAddress(optString(payload, "address"));
                JsonObject data = new JsonObject();
                data.add("selectedAddress", servers.statusSnapshot().get("selectedAddress"));
                respondOk(callback, id, data);
            }
            case "joinRoom" -> joinRoom(payload, id, callback);
            case "setSlide" -> {
                String deckId = requireDeckId(payload, id, callback);
                if (deckId == null) return;
                String slideId = optString(payload, "slideId");
                if (slideId == null) {
                    respondError(callback, id, "invalid slideId");
                    return;
                }
                int step = (int) optLong(payload, "step", 0);
                if (rooms.state(deckId) == null) {
                    respondError(callback, id, "unknown room");
                    return;
                }
                rooms.setSlide(deckId, slideId, step);
                respondOkSimple(callback, id);
            }
            case "nav" -> {
                String deckId = requireDeckId(payload, id, callback);
                if (deckId == null) return;
                if (rooms.state(deckId) == null) {
                    respondError(callback, id, "unknown room");
                    return;
                }
                String dir = optString(payload, "dir");
                rooms.nav(deckId, dir == null ? "next" : dir);
                respondOkSimple(callback, id);
            }
            case "reportSteps" -> {
                String deckId = requireDeckId(payload, id, callback);
                if (deckId == null) return;
                String slideId = optString(payload, "slideId");
                if (slideId == null) {
                    respondError(callback, id, "invalid slideId");
                    return;
                }
                rooms.reportSteps(deckId, slideId, (int) optLong(payload, "steps", 1));
                respondOkSimple(callback, id);
            }
            case "syncDeck" -> {
                String deckId = requireDeckId(payload, id, callback);
                if (deckId == null) return;
                List<SyncRooms.SlideRef> slides = SyncRooms.parseSlides(payload.get("slides"));
                if (slides.isEmpty()) {
                    respondError(callback, id, "invalid slides");
                    return;
                }
                if (!rooms.syncDeck(deckId, slides)) {
                    respondError(callback, id, "unknown room");
                    return;
                }
                respondOkSimple(callback, id);
            }
            case "setNotes" -> {
                String deckId = requireDeckId(payload, id, callback);
                if (deckId == null) return;
                String slideId = optString(payload, "slideId");
                if (slideId == null) {
                    respondError(callback, id, "invalid slideId");
                    return;
                }
                String note = optString(payload, "note");
                rooms.setNotes(deckId, slideId, note == null ? "" : note);
                respondOkSimple(callback, id);
            }
            case "setUi" -> {
                String deckId = requireDeckId(payload, id, callback);
                if (deckId == null) return;
                Boolean qr = optBoolean(payload, "qr");
                Boolean overview = optBoolean(payload, "overview");
                servers.relayUi(deckId, qr, overview);
                respondOkSimple(callback, id);
            }
            case "ping" -> {
                JsonObject data = new JsonObject();
                data.addProperty("t", System.currentTimeMillis());
                respondOk(callback, id, data);
            }
            case "openDownloadFolder" -> {
                DownloadBridge d = downloads;
                if (d == null) {
                    respondError(callback, id, "downloads are not available in this build");
                    return;
                }
                // opens the folder of the LAST completed download of this session
                // (or the configured downloads folder) — no arbitrary paths
                boolean opened = d.openLastDownloadFolder();
                JsonObject data = new JsonObject();
                data.addProperty("ok", opened);
                respondOk(callback, id, data);
            }
            default -> respondError(callback, id, "unknown action");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Protocol v2: hello + the fs commands                                */
    /* ------------------------------------------------------------------ */

    /**
     * Handles {@code {cmd, ...params}} requests. hello answers any page
     * (capability discovery — the same information level as getBridgeInfo);
     * every fs command requires the trusted app origin and runs on the fs
     * worker (file IO + native dialogs never block the CEF UI thread).
     */
    private void handleCmd(String cmd, JsonObject req, CefQueryCallback callback) {
        if ("hello".equals(cmd)) {
            JsonObject o = new JsonObject();
            o.addProperty("app", "chasse-desktop");
            o.addProperty("version", VERSION);
            com.google.gson.JsonArray caps = new com.google.gson.JsonArray();
            caps.add("sync");
            caps.add("fs");
            caps.add("downloads");
            o.add("capabilities", caps);
            callback.success(gson.toJson(o));
            return;
        }
        if (cmd.startsWith("fs.")) {
            if (fs == null) {
                respondCmdError(callback, FsService.CODE_IO_ERROR, "fs commands are not available");
                return;
            }
            if (!isTrusted()) {
                // only the configured app origin may touch the decks folder
                respondCmdError(callback, FsService.CODE_FORBIDDEN,
                        "this page is not allowed to use desktop file commands");
                return;
            }
            fsWorker.execute(() -> {
                try {
                    JsonElement data = dispatchFs(cmd, req);
                    callback.success(data == null ? "{}" : gson.toJson(data));
                } catch (FsService.FsException e) {
                    respondCmdError(callback, e.code, e.getMessage());
                } catch (Exception e) {
                    respondCmdError(callback, FsService.CODE_IO_ERROR,
                            "fs command failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            });
            return;
        }
        respondCmdError(callback, FsService.CODE_UNKNOWN_CMD, "unknown command: " + cmd);
    }

    /** Fixed command whitelist — anything else is UNKNOWN_CMD. */
    private JsonElement dispatchFs(String cmd, JsonObject req) throws FsService.FsException {
        switch (cmd) {
            case "fs.getRoot" -> {
                return fs.getRoot();
            }
            case "fs.chooseRoot" -> {
                return fs.chooseRoot(optString(req, "title"),
                        optString(req, "startPath"), optString(req, "hint"));
            }
            case "fs.listDecks" -> {
                return fs.listDecks();
            }
            case "fs.readText" -> {
                return fs.readText(requireParam(req, "deck"), requireParam(req, "path"));
            }
            case "fs.writeText" -> {
                return fs.writeText(requireParam(req, "deck"), requireParam(req, "path"),
                        optString(req, "text"));
            }
            case "fs.createDeck" -> {
                com.google.gson.JsonElement filesEl = req.get("files");
                if (filesEl == null || !filesEl.isJsonObject()) {
                    throw new FsService.FsException(FsService.CODE_BAD_REQUEST, "files map is required");
                }
                return fs.createDeck(requireParam(req, "deck"), filesEl.getAsJsonObject());
            }
            case "fs.deleteDeck" -> {
                return fs.deleteDeck(requireParam(req, "deck"));
            }
            case "fs.duplicateDeck" -> {
                return fs.duplicateDeck(requireParam(req, "deck"), optString(req, "copyId"),
                        optString(req, "title"));
            }
            case "fs.listFiles" -> {
                return fs.listFiles(requireParam(req, "deck"), optString(req, "dir"));
            }
            case "fs.delete" -> {
                return fs.delete(requireParam(req, "deck"), requireParam(req, "path"));
            }
            case "fs.fileUrl" -> {
                String deck = requireParam(req, "deck");
                String path = requireParam(req, "path");
                if (fileServer == null) {
                    throw new FsService.FsException(FsService.CODE_IO_ERROR, "file server is not running");
                }
                FsService.checkRelPath(path);
                JsonObject o = new JsonObject();
                o.addProperty("url", fileServer.urlFor(deck, path));
                return o;
            }
            default -> throw new FsService.FsException(FsService.CODE_UNKNOWN_CMD, "unknown command: " + cmd);
        }
    }

    /** Required string param — BAD_REQUEST with the field name when missing. */
    private static String requireParam(JsonObject req, String key) throws FsService.FsException {
        String v = optString(req, key);
        if (v == null || v.isBlank()) {
            throw new FsService.FsException(FsService.CODE_BAD_REQUEST, "missing required field: " + key);
        }
        return v;
    }

    /** v2 error response: {code, message} (the contract's error shape). */
    private void respondCmdError(CefQueryCallback callback, String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("code", code);
        o.addProperty("message", message == null ? "" : message);
        callback.success(gson.toJson(o));
    }

    private void joinRoom(JsonObject payload, JsonElement id, CefQueryCallback callback) {
        String deckId = requireDeckId(payload, id, callback);
        if (deckId == null) {
            return;
        }
        // role is informational (the bridge is always the trusted presenter side)
        String role = optString(payload, "role");
        if (role == null || (!"display".equals(role) && !"editor".equals(role))) {
            role = "editor";
        }
        List<SyncRooms.SlideRef> slides = SyncRooms.parseSlides(payload.get("slides"));
        SyncRooms.JoinResult result;
        try {
            result = rooms.join(deckId, slides);
        } catch (IllegalArgumentException e) {
            respondError(callback, id, "invalid deck id");
            return;
        }
        // slideId/step hint: apply when the room is fresh/empty so the presenter
        // resumes on their current slide instead of slide 1
        String slideId = optString(payload, "slideId");
        JsonObject stateNow = rooms.state(deckId);
        if (slideId != null && stateNow != null
                && (result.created || stateNow.get("slideId").isJsonNull())) {
            rooms.setSlide(deckId, slideId, (int) optLong(payload, "step", 0));
            stateNow = rooms.state(deckId);
        }
        if (result.created) {
            // the token was just minted for this room — tell the page
            JsonObject tokenEvent = new JsonObject();
            tokenEvent.addProperty("type", "sync-token");
            tokenEvent.addProperty("deckId", deckId);
            tokenEvent.addProperty("token", result.token);
            pushEvent(gson.toJson(tokenEvent));
        }
        JsonObject data = new JsonObject();
        data.add("state", stateNow == null ? JsonNull.INSTANCE : stateNow);
        data.addProperty("token", result.token);
        respondOk(callback, id, data);
    }

    /** Validates the deckId of a room action; answers the query and returns null when invalid. */
    private String requireDeckId(JsonObject payload, JsonElement id, CefQueryCallback callback) {
        String deckId = optString(payload, "deckId");
        if (deckId == null || !SyncRooms.DECK_ID.matcher(deckId).matches()) {
            respondError(callback, id, "invalid deck id");
            return null;
        }
        return deckId;
    }

    /* ------------------------------------------------------------------ */
    /* Event push (Java → web)                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Executes the event handler on the page — only when trusted.
     *
     * <p>The JSON is wrapped in a JavaScript STRING literal (Gson-escaped) so the
     * page receives exactly one argument of type string, matching the web lib's
     * {@code JSON.parse(json)}. Passing a raw object literal here is a protocol
     * violation that the page silently drops (PROBLEM 09).</p>
     */
    public void pushEvent(String json) {
        CefBrowser b = browser;
        if (b == null || !isTrusted()) {
            return;
        }
        try {
            String arg = gson.toJson(json); // JSON-encode the JSON text → a JS string literal
            b.executeJavaScript(
                    "window.__chasseDesktop && window.__chasseDesktop._handleEvent(" + arg + ")",
                    currentUrl, 0);
        } catch (Exception e) {
            // never throw into JCEF callers — but DO leave a trace for debugging
            System.err.println("[bridge] failed to push event to page: " + e);
        }
    }

    private final class ServerListener implements ServerManager.Listener {
        @Override
        public void onStatus(String status, int port, List<LanAddresses.LanAddress> addresses,
                             String selectedAddress, String error) {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("type", "server-status");
                o.addProperty("status", status);
                o.addProperty("port", port);
                com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                for (LanAddresses.LanAddress la : addresses) {
                    JsonObject e = new JsonObject();
                    e.addProperty("address", la.address());
                    e.addProperty("iface", la.iface());
                    e.addProperty("display", la.display());
                    e.addProperty("virtual", la.virtual());
                    arr.add(e);
                }
                o.add("addresses", arr);
                o.addProperty("selectedAddress", selectedAddress);
                o.add("error", error == null ? JsonNull.INSTANCE : gson.toJsonTree(error));
                System.out.println("[bridge] server status changed to " + status.toUpperCase(Locale.ROOT)
                        + " — sending server-status event to the page");
                pushEvent(gson.toJson(o));
            } catch (Exception e) {
                System.err.println("[bridge] failed to build server-status event: " + e);
            }
        }

        @Override
        public void onLog(long ts, String level, String text) {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("type", "server-log");
                o.addProperty("ts", ts);
                o.addProperty("level", level);
                o.addProperty("text", text);
                pushEvent(gson.toJson(o));
            } catch (Exception e) {
                System.err.println("[bridge] failed to build server-log event: " + e);
            }
        }
    }

    private final class RoomsListener implements SyncRooms.Listener {
        @Override
        public void onState(SyncRooms.Room room, String stateJson) {
            try {
                pushEvent("{\"type\":\"sync-state\",\"deckId\":" + gson.toJson(room.deckId)
                        + ",\"state\":" + stateJson + "}");
            } catch (Exception e) {
                System.err.println("[bridge] failed to build sync-state event: " + e);
            }
        }

        @Override
        public void onPresence(SyncRooms.Room room, int remotes) {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("type", "sync-presence");
                o.addProperty("deckId", room.deckId);
                o.addProperty("remotes", remotes);
                pushEvent(gson.toJson(o));
            } catch (Exception e) {
                System.err.println("[bridge] failed to build sync-presence event: " + e);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Response helpers                                                    */
    /* ------------------------------------------------------------------ */

    private void respondOk(CefQueryCallback callback, JsonElement id, JsonObject data) {
        JsonObject o = new JsonObject();
        o.add("id", id);
        o.addProperty("ok", true);
        o.add("data", data);
        callback.success(gson.toJson(o));
    }

    private void respondOkSimple(CefQueryCallback callback, JsonElement id) {
        JsonObject data = new JsonObject();
        data.addProperty("ok", true);
        respondOk(callback, id, data);
    }

    private void respondError(CefQueryCallback callback, JsonElement id, String error) {
        JsonObject o = new JsonObject();
        o.add("id", id);
        o.addProperty("ok", false);
        o.addProperty("error", error);
        callback.success(gson.toJson(o)); // errors also travel via onSuccess
    }

    /* ------------------------------------------------------------------ */
    /* Payload helpers                                                     */
    /* ------------------------------------------------------------------ */

    private static String optString(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            return el.getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static long optLong(JsonObject o, String key, long dflt) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return dflt;
        }
        try {
            return el.getAsLong();
        } catch (Exception e) {
            return dflt;
        }
    }

    private static Boolean optBoolean(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) {
            return null;
        }
        try {
            return el.getAsBoolean();
        } catch (Exception e) {
            return null;
        }
    }
}
