package com.chasse.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.javalin.Javalin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the embedded LAN sync server (Javalin: HTTP + WebSocket on one port)
 * plus its observable state: status, log ring buffer (last 200 lines) and the
 * LAN address list.
 *
 * <p>The {@link Listener} (installed by {@code DesktopBridge}) receives every
 * status change and every log line. The LAN address watcher always runs —
 * even while the server is stopped — so the panel can pre-select an interface.</p>
 */
public final class ServerManager {

    public enum Status {STOPPED, STARTING, RUNNING, ERROR}

    public static final int LOG_RING_CAPACITY = 200;

    /** One log line of the ring buffer: {ts, level, text}. */
    public static final class LogLine {
        public final long ts;
        public final String level;
        public final String text;

        LogLine(long ts, String level, String text) {
            this.ts = ts;
            this.level = level;
            this.text = text;
        }
    }

    public interface Listener {
        /**
         * Fired on EVERY status change — including selectedAddress changes and
         * network changes. {@code error} is null or the last error message.
         */
        void onStatus(String status, int port, List<LanAddresses.LanAddress> addresses,
                      String selectedAddress, String error);

        void onLog(long ts, String level, String text);
    }

    private final AppConfig config;
    private final SyncRooms rooms;
    private final PhoneWsHandler phoneWs;
    private final Gson gson = new Gson();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<LogLine> logRing = new ArrayDeque<>();

    private Status status = Status.STOPPED;
    private String lastError = null;
    private Javalin app = null;
    private final int port;
    private List<LanAddresses.LanAddress> addresses = new ArrayList<>();
    private String selectedAddress = null;
    private long startedAt = 0L;
    private ScheduledExecutorService heartbeat = null;

    public ServerManager(AppConfig config, SyncRooms rooms) {
        this.config = config;
        this.rooms = rooms;
        this.port = config.port();
        this.phoneWs = new PhoneWsHandler(rooms, this);
        // initial address scan (reported even while the server is stopped)
        this.addresses = LanAddresses.scan(config.preferredPrefix());
        this.selectedAddress = LanAddresses.pickPreferred(this.addresses);
        // watch the network forever (daemon thread, 5s poll)
        LanAddresses.startWatcher(config.preferredPrefix(), 5_000L, this::onAddressesChanged);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    /** Starts the server (STOPPED/ERROR → STARTING → RUNNING, or ERROR). */
    public synchronized void start() {
        if (status == Status.RUNNING || status == Status.STARTING) {
            return;
        }
        lastError = null;
        setStatus(Status.STARTING);

        // 1. probe the port first for a clear error message
        try (ServerSocket probe = new ServerSocket(port, 1, InetAddress.getByName("0.0.0.0"))) {
            // closed immediately by try-with-resources
        } catch (IOException e) {
            lastError = "Port " + port + " is already in use — close the other program or change server.port";
            status = Status.ERROR;
            log("error", lastError);
            fireStatus();
            return;
        }

        // 2. create + start Javalin on a background thread
        Thread starter = new Thread(this::doStart, "sync-server-start");
        starter.setDaemon(true);
        starter.start();
    }

    private void doStart() {
        Javalin created = null;
        try {
            created = Javalin.create(cfg -> cfg.showJavalinBanner = false);
            Javalin app = created;
            app.get("/remote", ctx -> ctx.contentType("text/html").result(RemotePage.html()));
            app.get("/health", ctx -> ctx.contentType("application/json")
                    .result("{\"ok\":true,\"status\":\"running\"}"));
            app.get("/api/status", ctx -> ctx.contentType("application/json").result(apiStatusJson()));
            app.ws("/ws", phoneWs::bind);
            app.events(e -> e.serverStarted(() -> {
                synchronized (ServerManager.this) {
                    if (status != Status.STARTING) {
                        return; // stopped while starting
                    }
                    status = Status.RUNNING;
                    startedAt = System.currentTimeMillis();
                    startHeartbeatLocked();
                    log("info", "Sync server running on port " + port);
                    fireStatus();
                }
            }));
            synchronized (this) {
                this.app = app;
            }
            app.start("0.0.0.0", port);
        } catch (Throwable t) {
            synchronized (this) {
                if (this.app == created) {
                    this.app = null;
                }
                lastError = "Failed to start sync server: " + t.getMessage();
                status = Status.ERROR;
                stopHeartbeatLocked();
                log("error", lastError);
                fireStatus();
            }
        }
    }

    /** Stops the server: bye to phones first, then Javalin stop (blocks). */
    public synchronized void stop() {
        if (status != Status.RUNNING && status != Status.STARTING) {
            return;
        }
        try {
            phoneWs.sendByeAll();
        } catch (Exception e) {
            log("warn", "Error while saying bye to phones: " + e.getMessage());
        }
        Javalin current = this.app;
        this.app = null;
        stopHeartbeatLocked();
        if (current != null) {
            try {
                current.stop(); // blocks until stopped
            } catch (Exception e) {
                log("warn", "Error stopping server: " + e.getMessage());
            }
        }
        status = Status.STOPPED;
        lastError = null;
        log("info", "Sync server stopped");
        fireStatus();
    }

    public synchronized void restart() {
        stop();
        start();
    }

    /* ------------------------------------------------------------------ */
    /* Heartbeat (only while running)                                      */
    /* ------------------------------------------------------------------ */

    private void startHeartbeatLocked() {
        stopHeartbeatLocked();
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-server-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                phoneWs.heartbeat();
            } catch (Exception e) {
                // heartbeat must never die
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void stopHeartbeatLocked() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* LAN addresses                                                       */
    /* ------------------------------------------------------------------ */

    private void onAddressesChanged(List<LanAddresses.LanAddress> next) {
        synchronized (this) {
            addresses = next;
            if (selectedAddress == null || indexOfAddress(selectedAddress) < 0) {
                selectedAddress = LanAddresses.pickPreferred(addresses);
            }
            log("info", "Network interfaces changed: " + formatAddresses(addresses));
            fireStatus(); // always fire on network changes
        }
    }

    /** Selects a LAN address (only if it is in the current list). */
    public synchronized void selectAddress(String address) {
        if (address != null && indexOfAddress(address) >= 0) {
            selectedAddress = address;
            fireStatus();
        }
    }

    private int indexOfAddress(String address) {
        for (int i = 0; i < addresses.size(); i++) {
            if (addresses.get(i).address().equals(address)) {
                return i;
            }
        }
        return -1;
    }

    private static String formatAddresses(List<LanAddresses.LanAddress> list) {
        if (list.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (LanAddresses.LanAddress la : list) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(la.address()).append(" (").append(la.display())
                    .append(la.virtual() ? ", virtual" : "").append(")");
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* Status snapshot / diagnostics                                       */
    /* ------------------------------------------------------------------ */

    /** Best address for links (selected or loopback fallback). */
    public synchronized String bestAddress() {
        return selectedAddress != null ? selectedAddress : "127.0.0.1";
    }

    /**
     * Full status snapshot for the bridge's getServerStatus:
     * {status, port, addresses, selectedAddress, error}.
     */
    public synchronized JsonObject statusSnapshot() {
        JsonObject o = new JsonObject();
        o.addProperty("status", statusString());
        o.addProperty("port", port);
        o.add("addresses", addressesArray());
        o.addProperty("selectedAddress", selectedAddress);
        o.add("error", lastError == null ? JsonNull.INSTANCE : gson.toJsonTree(lastError));
        return o;
    }

    /**
     * Ring-buffer snapshot for the bridge's getServerLog:
     * {lines: [{ts, level, text}, …]} — used to seed the panel's log view when
     * it opens (events stream the live lines afterwards).
     */
    public synchronized JsonObject logSnapshot() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (LogLine l : logRing) {
            JsonObject e = new JsonObject();
            e.addProperty("ts", l.ts);
            e.addProperty("level", l.level);
            e.addProperty("text", l.text);
            arr.add(e);
        }
        o.add("lines", arr);
        return o;
    }

    /** Diagnostics endpoint for a LAN browser. */
    private String apiStatusJson() {
        int roomCount;
        int phoneCount;
        String json;
        synchronized (this) {
            roomCount = rooms.roomIds().size();
            phoneCount = rooms.totalPhones();
            JsonObject o = new JsonObject();
            o.addProperty("status", statusString());
            o.addProperty("port", port);
            o.add("addresses", addressesArray());
            o.addProperty("selectedAddress", selectedAddress);
            o.addProperty("uptimeMs", status == Status.RUNNING && startedAt > 0
                    ? System.currentTimeMillis() - startedAt : 0);
            o.addProperty("rooms", roomCount);
            o.addProperty("phones", phoneCount);
            json = gson.toJson(o);
        }
        return json;
    }

    private JsonArray addressesArray() {
        JsonArray arr = new JsonArray();
        for (LanAddresses.LanAddress la : addresses) {
            JsonObject e = new JsonObject();
            e.addProperty("address", la.address());
            e.addProperty("iface", la.iface());
            e.addProperty("display", la.display());
            e.addProperty("virtual", la.virtual());
            arr.add(e);
        }
        return arr;
    }

    private String statusString() {
        switch (status) {
            case RUNNING:
                return "running";
            case STARTING:
                return "starting";
            case ERROR:
                return "error";
            default:
                return "stopped";
        }
    }

    /** Relay of setUi to the room's phones. */
    public void relayUi(String deckId, Boolean qr, Boolean overview) {
        phoneWs.relayUi(deckId, qr, overview);
    }

    /* ------------------------------------------------------------------ */
    /* Logging                                                             */
    /* ------------------------------------------------------------------ */

    /** Appends to the ring buffer (cap 200) and fires onLog. */
    public void log(String level, String text) {
        LogLine line;
        synchronized (this) {
            line = new LogLine(System.currentTimeMillis(), level, text);
            logRing.addLast(line);
            while (logRing.size() > LOG_RING_CAPACITY) {
                logRing.removeFirst();
            }
        }
        for (Listener l : listeners) {
            try {
                l.onLog(line.ts, line.level, line.text);
            } catch (Exception e) {
                // never let a listener break logging
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Listener plumbing                                                   */
    /* ------------------------------------------------------------------ */

    private void setStatus(Status next) {
        status = next;
        fireStatus();
    }

    private void fireStatus() {
        String statusStr = statusString();
        List<LanAddresses.LanAddress> snapshot = new ArrayList<>(addresses);
        String selected = selectedAddress;
        String error = lastError;
        for (Listener l : listeners) {
            try {
                l.onStatus(statusStr, port, snapshot, selected, error);
            } catch (Exception e) {
                // never let a listener break status updates
            }
        }
    }
}
