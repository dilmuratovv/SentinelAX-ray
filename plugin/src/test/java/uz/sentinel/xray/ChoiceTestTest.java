package uz.sentinel.xray;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ChoiceTestTest {
    /** One mining session: {@code turns} choices among 3 directions, each holding hidden ore with chance {@code density}. */
    private static ChoiceTest session(Random r, int turns, double density, double cheat) {
        ChoiceTest t = new ChoiceTest();
        for (int i = 0; i < turns; i++) {
            boolean[] ore = new boolean[3];
            int withOre = 0;
            for (int d = 0; d < 3; d++) if (ore[d] = r.nextDouble() < density) withOre++;
            int chosen = r.nextInt(3);
            // a cheater who sees the ore goes for it with probability "cheat"
            if (withOre > 0 && r.nextDouble() < cheat) for (int d = 0; d < 3; d++) if (ore[d]) { chosen = d; break; }
            t.choice(i, ore[chosen], 3, withOre);
        }
        return t;
    }

    @Test
    void blindMinersAlmostNeverFlag() {
        Random r = new Random(7);
        int flagged = 0, sessions = 20_000;
        for (int i = 0; i < sessions; i++) {
            double density = 0.05 + r.nextDouble() * 0.4;  // poor to rich areas
            if (session(r, 10 + r.nextInt(150), density, 0).suspicion(4, 1e-4) > 0) flagged++;
        }
        assertTrue(flagged <= 4, "false positives: " + flagged + " of " + sessions);
    }

    @Test
    void xrayMinersAreFoundWithinAFewDozenTurns() {
        Random r = new Random(11);
        int caught40 = 0, caught80 = 0, careful = 0;
        for (int i = 0; i < 1000; i++) if (session(r, 40, 0.2, 0.9).suspicion(4, 1e-4) > 0) caught40++;
        for (int i = 0; i < 1000; i++) if (session(r, 80, 0.2, 0.9).suspicion(4, 1e-4) > 0) caught80++;
        for (int i = 0; i < 1000; i++) if (session(r, 120, 0.2, 0.5).suspicion(4, 1e-4) > 0) careful++;
        System.out.println("x-ray caught: 40 turns " + caught40 + "/1000, 80 turns " + caught80 + "/1000, half-time cheat 120 turns " + careful + "/1000");
        assertTrue(caught40 >= 880, "40 turns: " + caught40);
        assertTrue(caught80 >= 990, "80 turns: " + caught80);
        assertTrue(careful >= 800, "a cheat that only goes for ore half the time: " + careful);
    }

    @Test
    void choicesWithoutAnythingToChooseAreIgnored() {
        ChoiceTest t = new ChoiceTest();
        t.choice(1, false, 3, 0);
        t.choice(2, true, 3, 3);
        t.choice(3, true, 1, 1);
        assertEquals(0, t.count());
    }

    @Test
    void exactTailProbability() {
        ChoiceTest t = new ChoiceTest();
        for (int i = 0; i < 10; i++) t.choice(i, true, 2, 1); // 10 hits at chance 1/2
        assertEquals(Math.pow(0.5, 10), t.pValue(), 1e-12);
        t.prune(100, 50);
        assertTrue(t.isEmpty());
    }
}
