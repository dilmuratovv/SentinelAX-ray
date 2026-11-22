package uz.sentinel.xray;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MiningStatsTest {
    @Test void legitBranchMiningIsNotSuspicious() {
        MiningStats s = new MiningStats();
        for (int vein = 0; vein < 8; vein++) {
            for (int i = 0; i < 120; i++) s.filler(0);
            s.vein(0, "diamond", false);
        }
        assertEquals(0, s.suspicion(5, 40));
    }

    @Test void diggingStraightToHiddenVeinsIsSuspicious() {
        MiningStats s = new MiningStats();
        for (int vein = 0; vein < 6; vein++) {
            for (int i = 0; i < 12; i++) s.filler(0);
            s.vein(0, "diamond", false);
        }
        int c = s.suspicion(5, 40);
        assertTrue(c > 70 && c <= 100, "confidence " + c);
    }

    @Test void exposedVeinsNeverCount() {
        MiningStats s = new MiningStats();
        for (int i = 0; i < 20; i++) s.vein(0, "diamond", true);
        assertEquals(0, s.suspicion(5, 40));
        assertEquals(20, s.exposedVeins());
    }

    @Test void needsEnoughVeins() {
        MiningStats s = new MiningStats();
        for (int i = 0; i < 4; i++) s.vein(0, "diamond", false);
        assertEquals(0, s.suspicion(5, 40));
        s.vein(0, "diamond", false);
        assertEquals(100, s.suspicion(5, 40));
    }

    @Test void windowForgetsOldMining() {
        MiningStats s = new MiningStats();
        for (int i = 0; i < 6; i++) s.vein(1000, "diamond", false);
        s.prune(1000 + 61_000, 60_000);
        assertEquals(0, s.hiddenVeins());
        assertTrue(s.isEmpty());
    }

    @Test void cooldown() {
        MiningStats s = new MiningStats();
        assertTrue(s.mayFlag(0, 1000));
        assertFalse(s.mayFlag(500, 1000));
        assertTrue(s.mayFlag(1500, 1000));
    }
}
