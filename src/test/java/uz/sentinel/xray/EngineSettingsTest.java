package uz.sentinel.xray;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineSettingsTest {
    private static YamlConfiguration engine() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("mode", 3);
        c.set("overworld.max-block-height", 70);
        c.set("overworld.hidden-blocks", List.of("diamond_ore", "minecraft:Gold_Ore", "diamond_ore", "bad name!"));
        c.set("overworld.replacement-blocks", List.of("stone"));
        c.set("nether.hidden-blocks", List.of("ancient_debris"));
        return c;
    }

    @Test void parsesAndNormalises() {
        List<String> warnings = new ArrayList<>();
        EngineSettings s = EngineSettings.from(engine(), "overworld", warnings::add);
        assertEquals(3, s.mode());
        assertEquals(64, s.maxBlockHeight(), "rounded down to a multiple of 16");
        assertEquals(List.of("diamond_ore", "gold_ore"), s.hiddenBlocks());
        assertEquals(2, warnings.size());
        assertEquals(true, s.values().get("enabled"));
        assertEquals(3, s.values().get("engine-mode"));
    }

    @Test void netherDefaultsAndInvalidMode() {
        YamlConfiguration c = engine();
        c.set("mode", 7);
        List<String> warnings = new ArrayList<>();
        EngineSettings s = EngineSettings.from(c, "nether", warnings::add);
        assertEquals(3, s.mode());
        assertEquals(128, s.maxBlockHeight());
        assertFalse(warnings.isEmpty());
    }

    @Test void writerAppliesOnceKeepsOtherKeysAndBacksUp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("paper-world.yml");
        Files.writeString(file, "_version: 31\nentities:\n  spawning:\n    monster-spawn-max-light-level: 0\nanticheat:\n  anti-xray:\n    enabled: false\n");
        EngineSettings s = EngineSettings.from(engine(), "overworld", w -> { });
        assertEquals(WorldConfigWriter.Result.CHANGED, WorldConfigWriter.apply(file, s, null, true));
        assertTrue(Files.readString(file).contains("enabled: false"), "dry run writes nothing");
        assertEquals(WorldConfigWriter.Result.CHANGED, WorldConfigWriter.apply(file, s, Boolean.TRUE, false));
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(31, y.getInt("_version"));
        assertEquals(0, y.getInt("entities.spawning.monster-spawn-max-light-level"));
        assertTrue(y.getBoolean("anticheat.anti-xray.enabled"));
        assertEquals(3, y.getInt("anticheat.anti-xray.engine-mode"));
        assertEquals(List.of("diamond_ore", "gold_ore"), y.getStringList("anticheat.anti-xray.hidden-blocks"));
        assertTrue(y.getBoolean("feature-seeds.generate-random-seeds-for-all"));
        assertTrue(Files.readString(dir.resolve(WorldConfigWriter.BACKUP)).contains("enabled: false"));
        assertEquals(WorldConfigWriter.Result.UNCHANGED, WorldConfigWriter.apply(file, s, Boolean.TRUE, false));
        assertTrue(WorldConfigWriter.matches(file, s, null));
    }

    @Test void writerCreatesMissingFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dimensions/minecraft/overworld/paper-world.yml");
        EngineSettings s = EngineSettings.from(engine(), "overworld", w -> { });
        assertEquals(WorldConfigWriter.Result.CHANGED, WorldConfigWriter.apply(file, s, null, false));
        assertTrue(WorldConfigWriter.matches(file, s, null));
        assertFalse(Files.exists(file.resolveSibling(WorldConfigWriter.BACKUP)), "nothing to back up");
    }

    @Test void sameComparesYamlTypesByMeaning() {
        assertTrue(WorldConfigWriter.same(64L, 64));
        assertTrue(WorldConfigWriter.same(List.of("minecraft:diamond_ore"), List.of("diamond_ore")));
        assertFalse(WorldConfigWriter.same(List.of("gold_ore"), List.of("diamond_ore")));
        assertFalse(WorldConfigWriter.same(null, true));
        assertTrue(WorldConfigWriter.same(true, true));
    }
}
