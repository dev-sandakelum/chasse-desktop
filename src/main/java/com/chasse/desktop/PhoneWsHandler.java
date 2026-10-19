package com.chasse.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint for phones ({@code /ws}).
 *
 * <p>Wires Javalin ws events to {@link SyncRooms} plus per-session bookkeeping
 * (sessionId → context, last activity, bound deck). Also acts as a
 * {@link SyncRooms.Listener} so every state/presence broadcast reaches the
 * phones of that room, no matter which transport caused it.</p>
 *
 * <p>Phone → control messages (nav / set-slide) require a successful token-checked
 * join; the session must be bound to the room.</p>
 */
public final class PhoneWsHandler implements SyncRooms.Listener {

    private static final String SESSION_ATTR = "chasse.sessionId";
    private static final long PHONE_TIMEOUT_MS = 15_000;

    private final SyncRooms rooms;
    private final ServerManager server; // for log lines
    private final Gson gson = new Gson();
    /** sessionId → session bookkeeping */
    private final Map<String, PhoneSession> sessions = new ConcurrentHashMap<>();

    private static final class PhoneSession {
        final WsContext ctx;
        volatile long lastActivity;
        /** Deck the phone joined (bound after a successful, token-checked join). */
        volatile String deckId;

        PhoneSession(WsContext ctx) {
            this.ctx = ctx;
            this.lastActivity = System.currentTimeMillis();
        }
    }

    public PhoneWsHandler(SyncRooms rooms, ServerManager server) {
        this.rooms = rooms;
        this.server = server;
        rooms.addListener(this);
    }

    /** Binds this handler to a Javalin ws route. */
    public void bind(WsConfig ws) {
        ws.onConnect(this::onConnect);
        ws.onMessage(this::onMessage);
        ws.onClose(this::onClose);
        ws.onError(this::onError);
    }

    /* ------------------------------------------------------------------ */
    /* Javalin ws events                                                   */
    /* ------------------------------------------------------------------ */

    private void onConnect(WsConnectContext ctx) {
        // generate our own session id (independent of Javalin's)
        String id = UUID.randomUUID().toString();
        ctx.attribute(SESSION_ATTR, id);
        sessions.put(id, new PhoneSession(ctx));
        server.log("info", "Phone connected (" + safeHost(ctx) + ")");
    }

    private void onMessage(WsMessageContext ctx) {
        PhoneSession session = sessionOf(ctx);
        if (session == null) {
            return;
        }
        session.lastActivity = System.currentTimeMillis();
        JsonObject msg;
        try {
            JsonElement parsed = JsonParser.parseString(ctx.message());
            if (!parsed.isJsonObject()) {
                return;
            }
            msg = parsed.getAsJsonObject();
        } catch (Exception e) {
            return; // ignore malformed frames
        }
        String type = optString(msg, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "join" -> handleJoin(session, msg);
            case "nav" -> handleNav(session, msg);
            case "set-slide" -> handleSetSlide(session, msg);
            case "get-state" -> handleGetState(session);
            case "ping" -> {
                long t = optLong(msg, "t", System.currentTimeMillis());
                send(session, "{\"type\":\"pong\",\"t\":" + t + "}");
            }
            case "pong" -> {
                // lastActivity already updated above
            }
            case "fullscreen" -> handleFullscreen(session, msg);
            default -> server.log("warn", "Unexpected phone message type: " + type);
        }
    }

    private void handleJoin(PhoneSession session, JsonObject msg) {
        String deckId = optString(msg, "deckId");
        String token = optString(msg, "token");
        String label = optString(msg, "label");
        if (label == null || label.isBlank()) {
            label = "Phone";
        }
        // a malformed deck id can never have a room → same error path
        if (deckId == null || !SyncRooms.DECK_ID.matcher(deckId).matches()) {
            sendError(session, "Unknown deck session");
            close(session);
            return;
        }
        String err = rooms.phoneJoin(deckId, token, label, sessionId(session));
        if (err != null) {
            sendError(session, err);
            close(session);
            return;
        }
        session.deckId = deckId;
        JsonObject state = rooms.state(deckId);
        String stateJson = state == null ? "null" : gson.toJson(state);
        send(session, "{\"type\":\"joined\",\"state\":" + stateJson + "}");
        server.log("info", "Phone joined room " + deckId + " (" + label + ")");
    }

    private void handleNav(PhoneSession session, JsonObject msg) {
        if (session.deckId == null) {
            return; // control requires a successful join
        }
        String dir = optString(msg, "dir");
        rooms.nav(session.deckId, dir == null ? "next" : dir);
        rooms.touch(session.deckId);
    }

    private void handleSetSlide(PhoneSession session, JsonObject msg) {
        if (session.deckId == null) {
            return; // control requires a successful join
        }
        String slideId = optString(msg, "slideId");
        if (slideId == null) {
            return;
        }
        int step = (int) optLong(msg, "step", 0);
        rooms.setSlide(session.deckId, slideId, Math.max(0, step));
        rooms.touch(session.deckId);
    }

    private void handleGetState(PhoneSession session) {
        if (session.deckId == null) {
            return;
        }
        JsonObject state = rooms.state(session.deckId);
        if (state != null) {
            send(session, "{\"type\":\"state\",\"state\":" + gson.toJson(state) + "}");
        }
    }

    /** Phone → desktop window fullscreen control (requires a successful join). */
    private void handleFullscreen(PhoneSession session, JsonObject msg) {
        if (session.deckId == null) {
            return; // control requires a successful join
        }
        String action = optString(msg, "action");
        if (action == null
                || (!action.equals("toggle") && !action.equals("enter") && !action.equals("exit"))) {
            action = "toggle";
        }
        rooms.touch(session.deckId);
        server.fireFullscreen(session.deckId, action);
        server.log("info", "Phone requested fullscreen '" + action + "' for room " + session.deckId);
    }

    private void onClose(WsCloseContext ctx) {
        teardown(sessionOf(ctx));
    }

    private void onError(WsErrorContext ctx) {
        // Javalin usually follows onError with onClose for the same session;
        // teardown is idempotent.
        teardown(sessionOf(ctx));
    }

    private void teardown(PhoneSession session) {
        if (session == null) {
            return;
        }
        String id = sessionId(session);
        if (sessions.remove(id) == null) {
            return; // already torn down
        }
        if (session.deckId != null) {
            String deckId = session.deckId;
            session.deckId = null;
            rooms.phoneLeave(deckId, id); // fires presence when the count changes
            server.log("info", "Phone left room " + deckId);
        } else {
            server.log("info", "Phone disconnected (" + safeHost(session.ctx) + ")");
        }
    }

    /* ------------------------------------------------------------------ */
    /* SyncRooms.Listener — broadcast state/presence to room phones        */
    /* ------------------------------------------------------------------ */

    @Override
    public void onState(SyncRooms.Room room, String stateJson) {
        broadcastToRoomPhones(room.deckId, "{\"type\":\"state\",\"state\":" + stateJson + "}");
    }

    @Override
    public void onPresence(SyncRooms.Room room, int remotes) {
        broadcastToRoomPhones(room.deckId, "{\"type\":\"presence\",\"remotes\":" + remotes + "}");
    }

    /* ------------------------------------------------------------------ */
    /* Helpers used by ServerManager                                       */
    /* ------------------------------------------------------------------ */

    /** Sends a raw JSON text to every phone session bound to the room. */
    public void broadcastToRoomPhones(String deckId, String json) {
        for (PhoneSession session : sessions.values()) {
            if (deckId.equals(session.deckId)) {
                send(session, json);
            }
        }
    }

    /** Server → phone: {"type":"ui","qr":..,"overview":..} (relay of setUi). */
    public void relayUi(String deckId, Boolean qr, Boolean overview) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "ui");
        if (qr != null) {
            o.addProperty("qr", qr);
        }
        if (overview != null) {
            o.addProperty("overview", overview);
        }
        broadcastToRoomPhones(deckId, gson.toJson(o));
    }

    /**
     * Server stop: say goodbye to every phone (so the page can show a clear
     * state instead of a silent drop) and close the sessions.
     */
    public void sendByeAll() {
        for (PhoneSession session : sessions.values()) {
            send(session, "{\"type\":\"bye\",\"reason\":\"server-stopping\"}");
        }
        for (PhoneSession session : Map.copyOf(sessions).values()) {
            String id = sessionId(session);
            sessions.remove(id);
            if (session.deckId != null) {
                String deckId = session.deckId;
                session.deckId = null;
                try {
                    rooms.phoneLeave(deckId, id);
                } catch (Exception e) {
                    // ignore
                }
            }
            try {
                session.ctx.closeSession();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Heartbeat (called every 5s while the server runs): close phones that have
     * been silent for more than 15s, ping the rest.
     */
    public void heartbeat() {
        long now = System.currentTimeMillis();
        for (PhoneSession session : sessions.values()) {
            if (now - session.lastActivity > PHONE_TIMEOUT_MS) {
                server.log("warn", "Phone timed out (" + safeHost(session.ctx) + ")");
                close(session);
            } else {
                send(session, "{\"type\":\"ping\",\"t\":" + now + "}");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Small utilities                                                     */
    /* ------------------------------------------------------------------ */

    private PhoneSession sessionOf(WsContext ctx) {
        Object id = ctx.attribute(SESSION_ATTR);
        return id == null ? null : sessions.get((String) id);
    }

    private String sessionId(PhoneSession session) {
        Object id = session.ctx.attribute(SESSION_ATTR);
        return id == null ? String.valueOf(System.identityHashCode(session)) : (String) id;
    }

    private void send(PhoneSession session, String json) {
        try {
            session.ctx.send(json);
        } catch (Exception e) {
            // socket already dead; the close handler will clean up
        }
    }

    private void sendError(PhoneSession session, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("message", message);
        send(session, gson.toJson(o));
    }

    private void close(PhoneSession session) {
        try {
            session.ctx.closeSession();
        } catch (Exception e) {
            // ignore
        }
    }

    private String safeHost(WsContext ctx) {
        try {
            String host = ctx.host();
            return host == null ? "unknown host" : host;
        } catch (Exception e) {
            return "unknown host";
        }
    }

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
}
