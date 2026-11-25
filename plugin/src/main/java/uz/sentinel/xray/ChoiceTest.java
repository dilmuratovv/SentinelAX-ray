package uz.sentinel.xray;

import java.util.ArrayDeque;

/**
 * "Does this player choose where hidden ore is?" A miner without x-ray cannot know which way leads to ore they cannot
 * see, so each time they choose a direction (a turn in a tunnel, a spot for TNT) the chance that their choice holds
 * hidden ore equals the share of the directions they could have taken that hold some. X-ray and ore simulators make
 * the chosen direction hit far more often. Every choice adds that chance; the number of hits is compared with the
 * Poisson-binomial distribution of those chances, so the test calibrates itself to how much ore the area really has.
 * Pure Java so it can be unit tested.
 */
public final class ChoiceTest {
    private record Choice(long time, boolean hit, double chance) {}

    private final ArrayDeque<Choice> choices = new ArrayDeque<>();

    /**
     * @param hit     the chosen direction held hidden valuable ore
     * @param options how many directions were open to choose from (the chosen one included)
     * @param withOre how many of them held hidden valuable ore (the chosen one included)
     */
    public void choice(long now, boolean hit, int options, int withOre) {
        if (options <= 1) return;
        double chance = Math.max(0, Math.min(1, withOre / (double) options));
        if (chance <= 0 || chance >= 1) return; // nothing to choose between: says nothing about the player
        choices.add(new Choice(now, hit, chance));
        while (choices.size() > 2000) choices.poll();
    }

    public void prune(long now, long windowMillis) {
        while (!choices.isEmpty() && now - choices.peek().time > windowMillis) choices.poll();
    }

    public int count() { return choices.size(); }
    public int hits() { int n = 0; for (Choice c : choices) if (c.hit) n++; return n; }
    public double expected() { double e = 0; for (Choice c : choices) e += c.chance; return e; }

    /** P(at least the observed hits | choices made blind), exact Poisson-binomial tail. */
    public double pValue() {
        int n = choices.size(), hits = hits();
        double[] dist = new double[n + 1];
        dist[0] = 1;
        int k = 0;
        for (Choice c : choices) {
            k++;
            for (int j = k; j >= 1; j--) dist[j] = dist[j] * (1 - c.chance) + dist[j - 1] * c.chance;
            dist[0] *= 1 - c.chance;
        }
        double tail = 0;
        for (int j = hits; j <= n; j++) tail += dist[j];
        return Math.min(1, tail);
    }

    /**
     * Confidence 0..100 that the choices were not blind, or 0. Needs {@code minHits} hits and a tail probability at or
     * below {@code maxP}; 1e-4 gives 70, every further factor 10 adds 10 (up to 100).
     */
    public int suspicion(int minHits, double maxP) {
        if (hits() < Math.max(1, minHits)) return 0;
        double p = pValue();
        if (p > maxP) return 0;
        double digits = -Math.log10(Math.max(p, 1e-300)) + Math.log10(maxP);
        return (int) Math.min(100, Math.round(70 + 10 * digits));
    }

    public boolean isEmpty() { return choices.isEmpty(); }
}
