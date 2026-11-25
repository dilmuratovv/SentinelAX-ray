package uz.sentinel.xray.los;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** What the network thread needs about one player, written by the main thread; positions grouped by chunk. */
final class OreView {
    final UUID id;
    volatile UUID world;
    volatile int minY;
    volatile boolean bypass;
    final Map<Long, Set<Long>> hidden = new ConcurrentHashMap<>();
    final Map<Long, Set<Long>> revealed = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<long[]> fresh = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<long[]> resend = new ConcurrentLinkedQueue<>();
    /** Main thread only: when each revealed ore was last in sight. */
    final Map<Long, Long> lastSeen = new java.util.HashMap<>();

    OreView(UUID id) { this.id = id; }

    static long chunkOf(long key) { return OreNms.chunkKey(OreNms.keyX(key) >> 4, OreNms.keyZ(key) >> 4); }

    boolean isHidden(long key) { Set<Long> s = hidden.get(chunkOf(key)); return s != null && s.contains(key); }
    boolean isRevealed(long key) { Set<Long> s = revealed.get(chunkOf(key)); return s != null && s.contains(key); }

    void hide(long key) {
        hidden.computeIfAbsent(chunkOf(key), k -> ConcurrentHashMap.newKeySet()).add(key);
        Set<Long> r = revealed.get(chunkOf(key));
        if (r != null) r.remove(key);
    }

    void unhide(long key) { Set<Long> s = hidden.get(chunkOf(key)); if (s != null) s.remove(key); }

    void markRevealed(long key) {
        unhide(key);
        revealed.computeIfAbsent(chunkOf(key), k -> ConcurrentHashMap.newKeySet()).add(key);
    }

    void dropChunk(long chunk) { hidden.remove(chunk); revealed.remove(chunk); }
    void clear() { hidden.clear(); revealed.clear(); fresh.clear(); resend.clear(); lastSeen.clear(); }
}
