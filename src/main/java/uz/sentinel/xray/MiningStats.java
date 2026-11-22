package uz.sentinel.xray;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * One player's recent mining, inside a sliding time window. Pure Java so it can be unit tested.
 *
 * <p>Counts filler blocks (stone, deepslate, netherrack...) and valuable ore veins, split into hidden veins (no
 * pre-existing opening touched them) and exposed veins (visible from a cave or another tunnel).</p>
 */
public final class MiningStats {
    private record Event(long time, int kind, String group) {}

    private static final int FILLER = 0, HIDDEN = 1, EXPOSED = 2;

    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long lastFlag = Long.MIN_VALUE;

    public void filler(long now) { events.add(new Event(now, FILLER, null)); }

    public void vein(long now, String group, boolean exposed) { events.add(new Event(now, exposed ? EXPOSED : HIDDEN, group)); }

    public void prune(long now, long windowMillis) {
        Iterator<Event> it = events.iterator();
        while (it.hasNext() && now - it.next().time > windowMillis) it.remove();
        // keep memory bounded even for a player who mines all day without a pause
        while (events.size() > 20000) events.poll();
    }

    public int fillers() { int n = 0; for (Event e : events) if (e.kind == FILLER) n++; return n; }
    public int hiddenVeins() { int n = 0; for (Event e : events) if (e.kind == HIDDEN) n++; return n; }
    public int exposedVeins() { int n = 0; for (Event e : events) if (e.kind == EXPOSED) n++; return n; }

    /**
     * Confidence (0..100) that the window shows x-ray mining, or 0 when it does not. Needs at least {@code minVeins}
     * hidden veins; the fewer filler blocks were mined per hidden vein, the higher the confidence.
     */
    public int suspicion(int minVeins, double maxFillerPerVein) {
        int hidden = hiddenVeins();
        if (hidden < Math.max(1, minVeins)) return 0;
        double perVein = fillers() / (double) hidden;
        if (perVein >= maxFillerPerVein) return 0;
        // 0 filler per vein -> 100; just under the limit -> 50
        return (int) Math.round(100 - 50 * (perVein / maxFillerPerVein));
    }

    /** True (and restarts the cooldown) when no flag was raised within the cooldown. */
    public boolean mayFlag(long now, long cooldownMillis) {
        if (lastFlag != Long.MIN_VALUE && now - lastFlag < cooldownMillis) return false;
        lastFlag = now;
        return true;
    }

    public boolean isEmpty() { return events.isEmpty(); }
}
