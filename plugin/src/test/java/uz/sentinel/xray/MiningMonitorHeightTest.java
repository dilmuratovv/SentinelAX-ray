package uz.sentinel.xray;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MiningMonitorHeightTest {
    @Test
    void overworldVeinsAboveFillerHeightAreNotJudged() {
        assertTrue(MiningMonitor.measurable(false, -58, 64));
        assertTrue(MiningMonitor.measurable(false, 64, 64));
        assertFalse(MiningMonitor.measurable(false, 65, 64), "mountain emeralds: filler there is not counted");
        assertFalse(MiningMonitor.measurable(false, 180, 64));
    }

    @Test
    void netherIsJudgedAtEveryHeight() {
        assertTrue(MiningMonitor.measurable(true, 15, 64));
        assertTrue(MiningMonitor.measurable(true, 120, 64));
    }

    @Test
    void mountainEmeraldMinerIsNoLongerFlagged() {
        // Before the fix: 5 hidden emerald veins at y>64 counted, the stone dug to reach them did not -> 100%.
        MiningStats st = new MiningStats();
        long t = 1_000;
        for (int vein = 0; vein < 6; vein++) {
            for (int dug = 0; dug < 30; dug++) {
                if (MiningMonitor.measurable(false, 150, 64)) st.filler(t);
            }
            if (MiningMonitor.measurable(false, 150, 64)) st.vein(t, "emerald", false);
            t += 1000;
        }
        assertEquals(0, st.suspicion(5, 40));
    }
}
