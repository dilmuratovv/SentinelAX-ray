package uz.sentinel.xray.los;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class OreLayerTest {
    @Test
    void standInsMatchTheRockAround() {
        assertEquals(Material.STONE, OreLayer.standIn(Material.DIAMOND_ORE));
        assertEquals(Material.DEEPSLATE, OreLayer.standIn(Material.DEEPSLATE_DIAMOND_ORE));
        assertEquals(Material.NETHERRACK, OreLayer.standIn(Material.ANCIENT_DEBRIS));
        assertEquals(Material.NETHERRACK, OreLayer.standIn(Material.NETHER_GOLD_ORE));
        assertEquals(Material.STONE, OreLayer.standIn(Material.EMERALD_ORE));
    }

    @Test
    void positionKeysRoundTrip() {
        for (int[] p : new int[][]{{0, 0, 0}, {-30000000, -64, 29999999}, {123, 319, -456}, {-1, -1, -1}}) {
            long k = OreNms.key(p[0], p[1], p[2]);
            assertEquals(p[0], OreNms.keyX(k));
            assertEquals(p[1], OreNms.keyY(k));
            assertEquals(p[2], OreNms.keyZ(k));
        }
    }
}
