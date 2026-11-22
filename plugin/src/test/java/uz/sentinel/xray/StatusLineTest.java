package uz.sentinel.xray;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StatusLineTest {
    @Test
    void onlyInactiveEnginesNeedAction() {
        assertTrue(SentinelAXray.needsAction("world: running off; config not applied (/sxray apply)"));
        assertTrue(SentinelAXray.needsAction("world: running off; config applied, restart Paper to activate"));
        assertTrue(SentinelAXray.needsAction("world: running off; config unreadable: bad yaml"));
        assertFalse(SentinelAXray.needsAction("world: running engine-mode 3 up to y=64; config applied"));
        assertFalse(SentinelAXray.needsAction("world: running unknown (X); config applied (running engine could not be read on this Paper build)"));
    }
}
