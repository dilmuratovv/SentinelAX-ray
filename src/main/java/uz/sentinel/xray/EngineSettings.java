package uz.sentinel.xray;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;

/**
 * What Paper's native anti-xray engine should look like for one kind of dimension, read from this plugin's config.
 * {@link #values()} gives the exact keys written under {@code anticheat.anti-xray} in a world's paper-world.yml.
 */
public record EngineSettings(int mode, int maxBlockHeight, int updateRadius, boolean lavaObscures, boolean usePermission,
                             List<String> hiddenBlocks, List<String> replacementBlocks) {

    public static final String PREFIX = "anticheat.anti-xray.";

    /**
     * @param dimension "overworld" or "nether"
     */
    public static EngineSettings from(ConfigurationSection engine, String dimension, Consumer<String> warn) {
        int mode = engine.getInt("mode", 3);
        if (mode < 1 || mode > 3) { warn.accept("engine.mode must be 1, 2 or 3; using 3"); mode = 3; }
        ConfigurationSection dim = engine.getConfigurationSection(dimension);
        int fallbackHeight = dimension.equals("nether") ? 128 : 64;
        int height = dim == null ? fallbackHeight : dim.getInt("max-block-height", fallbackHeight);
        // Paper only accepts multiples of 16 (it rounds down); keep what we write identical to what runs.
        int rounded = Math.max(16, Math.floorDiv(height, 16) * 16);
        if (rounded != height) warn.accept(dimension + ".max-block-height " + height + " rounded to " + rounded);
        List<String> hidden = blocks(dim, "hidden-blocks", warn);
        List<String> replacement = blocks(dim, "replacement-blocks", warn);
        if (hidden.isEmpty()) warn.accept(dimension + ".hidden-blocks is empty: nothing will be hidden there");
        int radius = Math.max(1, Math.min(2, engine.getInt("update-radius", 2)));
        return new EngineSettings(mode, rounded, radius, engine.getBoolean("lava-obscures", false),
                engine.getBoolean("use-permission", false), hidden, replacement);
    }

    private static List<String> blocks(ConfigurationSection dim, String key, Consumer<String> warn) {
        List<String> out = new ArrayList<>();
        if (dim == null) return out;
        for (String raw : dim.getStringList(key)) {
            String name = raw.trim().toLowerCase(Locale.ROOT);
            if (name.startsWith("minecraft:")) name = name.substring(10);
            if (!name.matches("[a-z0-9_]+")) { warn.accept("Ignoring invalid block name in " + key + ": " + raw); continue; }
            if (!out.contains(name)) out.add(name);
        }
        return out;
    }

    /** Keys (relative to {@link #PREFIX}) and values in the form Paper reads them. */
    public Map<String, Object> values() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", true);
        m.put("engine-mode", mode);
        m.put("max-block-height", maxBlockHeight);
        m.put("update-radius", updateRadius);
        m.put("lava-obscures", lavaObscures);
        m.put("use-permission", usePermission);
        m.put("hidden-blocks", List.copyOf(hiddenBlocks));
        m.put("replacement-blocks", List.copyOf(replacementBlocks));
        return m;
    }
}
