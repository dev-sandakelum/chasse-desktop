package com.chasse.desktop;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * In-process room state machine — a faithful port of the old
 * {@code mini-services/stagesmith-sync} node service (join/clamp/nav logic),
 * shared by both transports:
 *
 * <ul>
 *   <li>the web page via the JCEF bridge ({@code DesktopBridge})</li>
 *   <li>the phones via WebSocket ({@code PhoneWsHandler})</li>
 * </ul>
 *
 * <p>SyncRooms serializes the state JSON itself so both transports broadcast the
 * exact same payload. The bridge ("presenter"/"editor") is implicit — the app
 * itself — and is delivered state/presence via the {@link Listener}.</p>
 *
 * <p>All public methods are thread-safe (synchronized on an internal lock).
 * Listeners are invoked while the lock is held to guarantee event ordering;
 * listeners must therefore never call back into this class.</p>
 */
public final class SyncRooms {

    /** Same rule as the old service. */
    public static final Pattern DECK_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    /** Max number of slides accepted in join/syncDeck payloads. */
    public static final int MAX_SLIDES = 500;

    private static final long EVICT_AFTER_MS = 2 * 60 * 60 * 1000L; // 2h idle → evict
    private static final long SWEEP_INTERVAL_MS = 10 * 60 * 1000L;  // sweep every 10 min

    public static final class SlideRef {
        public String id;
        public String label;

        public SlideRef() {
        }

        public SlideRef(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    public static final class Room {
        public String deckId;
        public String slideId;
        public Integer index;
        public int step;
        public int steps;
        public List<SlideRef> order = new ArrayList<>();
        public Map<String, Integer> stepsMap = new HashMap<>();
        public Map<String, String> notes = new HashMap<>();
        public String token;
        /** sessionId → label */
        public Map<String, String> remotes = new LinkedHashMap<>();
        public long updatedAt;
        public long lastActive;
    }

    /** Result of {@link #join}: canonical state JSON plus the room token. */
    public static final class JoinResult {
        public final String state;
        public final String token;
        /** True when this call created the room (drives the sync-token event). */
        public final boolean created;

        JoinResult(String state, String token, boolean created) {
            this.state = state;
            this.token = token;
            this.created = created;
        }
    }

    /**
     * Receives state/presence broadcasts. {@code stateJson} is the canonical
     * payload {@code {"deckId","slideId","index","step","steps","order","note","updatedAt"}}.
     */
    public interface Listener {
        void onState(Room room, String stateJson);

        void onPresence(Room room, int remotes);
    }

    private final Object lock = new Object();
    private final Map<String, Room> rooms = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Gson gson = new Gson();
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService sweeper;

    public SyncRooms() {
        sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-rooms-sweeper");
            t.setDaemon(true);
            return t;
        });
        sweeper.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /* ------------------------------------------------------------------ */
    /* Bridge API                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Join (or create) a room, optionally applying an incoming slide order —
     * ported from the old service's join handler.
     */
    public JoinResult join(String deckId, List<SlideRef> slides) {
        if (deckId == null || !DECK_ID.matcher(deckId).matches()) {
            throw new IllegalArgumentException("invalid deck id");
        }
        synchronized (lock) {
            Room room = rooms.get(deckId);
            boolean created = room == null;
            if (created) {
                room = newRoom(deckId);
                rooms.put(deckId, room);
            }
            room.lastActive = now();

            boolean mutated = created;
            if (slides != null && !slides.isEmpty()) {
                List<SlideRef> next = sanitizeSlides(slides);
                boolean same = next.size() == room.order.size();
                if (same) {
                    for (int i = 0; i < next.size(); i++) {
                        if (!Objects.equals(next.get(i).id, room.order.get(i).id)) {
                            same = false;
                            break;
                        }
                    }
                }
                if (!same && !next.isEmpty()) {
                    room.order = next;
                    mutated = true;
                    if (room.slideId == null) {
                        room.slideId = room.order.isEmpty() ? null : room.order.get(0).id;
                        room.index = 0;
                        room.step = 0;
                        room.steps = stepsOf(room, room.slideId);
                    } else {
                        int idx = indexOf(room.order, room.slideId);
                        if (idx >= 0) {
                            room.index = idx;
                        } else {
                            room.index = 0;
                            room.slideId = room.order.isEmpty() ? null : room.order.get(0).id;
                            room.step = 0;
                        }
                        room.steps = stepsOf(room, room.slideId);
                    }
                }
            }
            clampToRoom(room);
            // belt-and-braces (rooms always mint a token at creation, but keep the
            // old service's healing behaviour)
            if (room.token == null) {
                room.token = mintToken();
            }

            if (mutated) {
                room.updatedAt = now();
            }
            String stateJson = gson.toJson(stateObject(room));
            if (mutated) {
                fireState(room, stateJson);
            }
            // the bridge join always refreshes presence on the page (like the old
            // service, which broadcast presence on every join)
            firePresence(room);
            return new JoinResult(stateJson, room.token, created);
        }
    }

    /** Canonical state object for a room, or null when the room does not exist. */
    public JsonObject state(String deckId) {
        synchronized (lock) {
            Room room = rooms.get(deckId);
            if (room == null) {
                return null;
            }
            room.lastActive = now();
            return stateObject(room);
        }
    }

    /** Port of the old set-slide (index derived from the order; step clamped). */
    public boolean setSlide(String deckId, String slideId, int step) {
        synchronized (lock) {
            Room room = rooms.get(deckId);
            if (room == null) {
                return false;
            }
            room.lastActive = now();
            int idx = indexOf(room.order, slideId);
            if (idx < 0) {
                return false;
            }
            room.index = idx;
            room.slideId = slideId;
            room.steps = stepsOf(room, slideId);
            room.step = Math.max(0, Math.min(step, room.steps - 1));
            fireState(room, publish(room));
            return true;
        }
    }

    /**
     * Port of the old nav(): resolve steps within the slide first, then slides —
     * "prev" from step 0 goes to the LAST step of the previous slide.
     */
    public boolean nav(String deckId, String dir) {
        synchronized (lock) {
            Room room = rooms.get(deckId);
            if (room == null) {
                return false;
            }
            room.lastActive = now();
            boolean next = !"prev".equals(dir);
            if (next) {
                if (room.step < stepsOf(room, room.slideId) - 1) {
                    room.step += 1;
                } else if (room.index != null && room.index < room.order.size() - 1) {
                    room.index += 1;
                    room.slideId = room.order.get(room.index).id;
                    room.step = 0;
                    room.steps = stepsOf(room, room.slideId);
                } else {
                    return false; // at the end
                }
            } else {
                if (room.step > 0) {
                    room.step -= 1;
                } else if (room.index != null && room.index > 0) {
                    room.index -= 1;
                    room.slideId = room.order.get(room.index).id;
                    int prevSteps = stepsOf(room, room.slideId);
                    room.step = Math.max(0, prevSteps - 1);
                    room.steps = prevSteps;
                } else {
                    return false; // at the start
                }
            }
            fireState(room, publish(room));
            return true;
        }
    }

    /** Port of report-steps: clamp 1..50, fix current slide's step if needed. */
    public void reportSteps(String deckId, String slideId, int steps) {
        synchronized (lock) {
            Room room = rooms.get(deckId);
            if (room == null || slideId == null) {
                return;
            }
            room.lastActive = now();
            int n = Math.max(1, Math.min(50, steps));
            room.stepsMap.put(slideId, n);
            boolean changed = false;
            if (slideId.equals(room.slideId)) {
                if (room.steps != n) {
                    room.steps = n;
                    changed = true;
                }
                if (room.step > n - 1) {
                    room.step = n - 1;
                    changed = true;
                }
            }
            if (changed) {
                fireState(room, publish(room));
            }
        }
    }

    /** Port of sync-deck (display re-syncs the order after edits). */
    public boolean syncDeck(String deckId, List<SlideRef> slides) {
        synchronized (lock) {
            Room room = rooms.get(deckId);
            if (room == null) {
                return false;
            }
            room.lastActive = now();
            List<SlideRef> next = sanitizeSlides(slides);
            if (next.isEmpty()) {
                return false;
            }
            room.order = next;
            clampToRoom(room);
            fireState(room, publish(room));
            return true;
        }
    }

    /** Notes of a slide (cap 8000 chars, strip U+2028/U+2029 line separators). */
    public void setNotes(String deckId, String slideId, String note) {
        synchronized (lock) {
            Room room = rooms.get(deckId);
            if (room == null || slideId == null) {
                return;
            }
            room.lastActive = now();
            String cleaned = note == null ? "" : note;
            if (cleaned.length() > 8000) {
                cleaned = cleaned.substring(0, 8000);
            }
            cleaned = cleaned.replace("\u2028", "").replace("\u2029", "");
            String prev = room.notes.get(slideId);
            room.notes.put(slideId, cleaned);
            if (slideId.equals(room.slideId) && !cleaned.equals(prev)) {
                fireState(room, publish(room));
            }
        }
    }

    public Set<String> roomIds() {
        synchronized (lock) {
            return new HashSet<>(rooms.keySet());
        }
    }

    public int totalPhones() {
        synchronized (lock) {
            int total = 0;
            for (Room room : rooms.values()) {
                total += room.remotes.size();
            }
            return total;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Phone sessions                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Registers a phone in a room (token-checked).
     *
     * @return null on success, or an error message for the phone.
     */
    public String phoneJoin(String deckId, String token, String label, String sessionId) {
        synchronized (lock) {
            Room room = rooms.get(deckId);
            if (room == null) {
                return "Unknown deck session";
            }
            if (token == null || !token.equals(room.token)) {
                return "Invalid or expired link token";
            }
            room.lastActive = now();
            String safeLabel = (label == null || label.isBlank()) ? "Phone" : label;
            int before = room.remotes.size();
            room.remotes.put(sessionId, safeLabel);
            if (room.remotes.size() != before) {
                firePresence(room);
            }
            return null;
        }
    }

    public void phoneLeave(String deckId, String sessionId) {
        synchronized (lock) {
            Room room = rooms.get(deckId);
            if (room == null) {
                return;
            }
            room.lastActive = now();
            if (room.remotes.remove(sessionId) != null) {
                firePresence(room);
            }
        }
    }

    /** Refreshes a room's idle timer. */
    public void touch(String deckId) {
        synchronized (lock) {
            Room room = rooms.get(deckId);
            if (room != null) {
                room.lastActive = now();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Internals                                                           */
    /* ------------------------------------------------------------------ */

    private Room newRoom(String deckId) {
        Room room = new Room();
        room.deckId = deckId;
        room.slideId = null;
        room.index = 0;
        room.step = 0;
        room.steps = 1;
        room.token = mintToken();
        long now = now();
        room.updatedAt = now;
        room.lastActive = now;
        return room;
    }

    private String mintToken() {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(12);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static int indexOf(List<SlideRef> order, String slideId) {
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).id.equals(slideId)) {
                return i;
            }
        }
        return -1;
    }

    private static int stepsOf(Room room, String slideId) {
        if (slideId == null) {
            return 1;
        }
        Integer steps = room.stepsMap.get(slideId);
        return steps == null || steps < 1 ? 1 : steps;
    }

    /** Port of the old clampToRoom(). */
    private static void clampToRoom(Room room) {
        if (room.order.isEmpty()) {
            return;
        }
        room.index = Math.max(0, Math.min(room.index, room.order.size() - 1));
        SlideRef cur = room.order.get(room.index);
        if (cur != null && !cur.id.equals(room.slideId)) {
            room.slideId = cur.id;
            room.step = 0;
            room.steps = stepsOf(room, cur.id);
        }
    }

    private static List<SlideRef> sanitizeSlides(List<SlideRef> slides) {
        List<SlideRef> out = new ArrayList<>();
        for (SlideRef s : slides) {
            if (s == null || s.id == null || s.id.isEmpty() || s.label == null) {
                continue;
            }
            out.add(new SlideRef(s.id, s.label));
            if (out.size() >= MAX_SLIDES) {
                break; // hard cap
            }
        }
        return out;
    }

    /**
     * The canonical state payload — identical for both transports:
     * {@code {"deckId","slideId","index","step","steps","order":[{id,label}],"note","updatedAt"}}.
     */
    private JsonObject stateObject(Room room) {
        JsonObject o = new JsonObject();
        o.addProperty("deckId", room.deckId);
        if (room.slideId == null) {
            o.add("slideId", JsonNull.INSTANCE);
        } else {
            o.addProperty("slideId", room.slideId);
        }
        o.addProperty("index", room.index == null ? 0 : room.index);
        o.addProperty("step", room.step);
        o.addProperty("steps", room.steps);
        JsonArray order = new JsonArray();
        for (SlideRef s : room.order) {
            JsonObject e = new JsonObject();
            e.addProperty("id", s.id);
            e.addProperty("label", s.label);
            order.add(e);
        }
        o.add("order", order);
        String note = room.slideId == null ? "" : room.notes.getOrDefault(room.slideId, "");
        o.addProperty("note", note == null ? "" : note);
        o.addProperty("updatedAt", room.updatedAt);
        return o;
    }

    /** Stamps updatedAt and returns the serialized state. */
    private String publish(Room room) {
        room.updatedAt = now();
        return gson.toJson(stateObject(room));
    }

    private void fireState(Room room, String stateJson) {
        for (Listener l : listeners) {
            try {
                l.onState(room, stateJson);
            } catch (Exception e) {
                // one bad listener must never break the broadcast
            }
        }
    }

    private void firePresence(Room room) {
        int remotes = room.remotes.size();
        for (Listener l : listeners) {
            try {
                l.onPresence(room, remotes);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /** Idle eviction: no phones + no activity for 2h → drop the room. */
    private void sweep() {
        long now = now();
        synchronized (lock) {
            rooms.entrySet().removeIf(entry -> {
                Room room = entry.getValue();
                return room.remotes.isEmpty() && now - room.lastActive > EVICT_AFTER_MS;
            });
        }
    }

    /** Parses a slides array [{id,label}] from a bridge payload (defensive). */
    public static List<SlideRef> parseSlides(JsonElement slidesEl) {
        List<SlideRef> out = new ArrayList<>();
        if (slidesEl == null || !slidesEl.isJsonArray()) {
            return out;
        }
        for (JsonElement el : slidesEl.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            JsonElement id = o.get("id");
            JsonElement label = o.get("label");
            if (id == null || !id.isJsonPrimitive() || id.getAsString() == null
                    || label == null || !label.isJsonPrimitive()) {
                continue;
            }
            out.add(new SlideRef(id.getAsString(), label.getAsString()));
            if (out.size() >= MAX_SLIDES) {
                break;
            }
        }
        return out;
    }

}
