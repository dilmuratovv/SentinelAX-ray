package uz.sentinel.xray;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VeinAnalyzerTest {
    /** Everything is stone unless set: "d" = diamond, "a" = air. */
    private static final class World implements VeinAnalyzer.Grid {
        final Map<Long, String> blocks = new HashMap<>();
        World set(int x, int y, int z, String b) { blocks.put(VeinAnalyzer.key(x, y, z), b); return this; }
        @Override public String group(int x, int y, int z) { return "d".equals(blocks.get(VeinAnalyzer.key(x, y, z))) ? "diamond" : null; }
        @Override public boolean open(int x, int y, int z) { return "a".equals(blocks.get(VeinAnalyzer.key(x, y, z))); }
    }

    @Test void enclosedVeinIsHidden() {
        World w = new World().set(0, -50, 0, "d").set(1, -50, 0, "d").set(1, -49, 1, "d"); // diagonal joins
        VeinAnalyzer.Vein v = VeinAnalyzer.analyze(w, 0, -50, 0, Set.of(), 32);
        assertEquals("diamond", v.group());
        assertEquals(3, v.size());
        assertFalse(v.exposed());
    }

    @Test void caveAirExposesVein() {
        World w = new World().set(0, -50, 0, "d").set(1, -50, 0, "d").set(2, -50, 0, "a");
        assertTrue(VeinAnalyzer.analyze(w, 0, -50, 0, Set.of(), 32).exposed());
    }

    @Test void minersOwnTunnelDoesNotExpose() {
        World w = new World().set(0, -50, 0, "d");
        Set<Long> dug = new HashSet<>();
        for (int x = -10; x < 0; x++) { w.set(x, -50, 0, "a"); w.set(x, -49, 0, "a"); dug.add(VeinAnalyzer.key(x, -50, 0)); dug.add(VeinAnalyzer.key(x, -49, 0)); }
        assertFalse(VeinAnalyzer.analyze(w, 0, -50, 0, dug, 32).exposed());
        assertTrue(VeinAnalyzer.analyze(w, 0, -50, 0, Set.of(), 32).exposed(), "the same air dug by someone else exposes it");
    }

    @Test void sizeIsLimited() {
        World w = new World();
        for (int x = 0; x < 100; x++) w.set(x, 0, 0, "d");
        VeinAnalyzer.Vein v = VeinAnalyzer.analyze(w, 0, 0, 0, Set.of(), 32);
        assertEquals(32, v.size());
        assertEquals(32, v.blocks().size());
    }

    @Test void nonOreIsNoVein() {
        assertNull(VeinAnalyzer.analyze(new World(), 0, 0, 0, Set.of(), 32).group());
    }

    @Test void keysAreDistinctForNegativeCoordinates() {
        Set<Long> keys = new HashSet<>();
        for (int x = -3; x <= 3; x++) for (int y = -64; y <= -60; y++) for (int z = -3; z <= 3; z++) assertTrue(keys.add(VeinAnalyzer.key(x, y, z)));
    }
}
