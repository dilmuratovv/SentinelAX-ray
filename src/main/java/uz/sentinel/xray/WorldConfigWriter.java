package uz.sentinel.xray;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Writes engine settings into one world's paper-world.yml: backup once, atomic replace, untouched other keys. */
public final class WorldConfigWriter {
    public static final String BACKUP = "paper-world.before-sentinelaxray.yml";

    public enum Result { CHANGED, UNCHANGED }

    private WorldConfigWriter() {}

    /** True when the file already holds exactly these settings (and the feature-seed choice, if managed). */
    public static boolean matches(Path file, EngineSettings s, Boolean randomFeatureSeeds) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = load(file);
        for (Map.Entry<String, Object> e : s.values().entrySet()) {
            Object have = yaml.get(EngineSettings.PREFIX + e.getKey());
            if (!same(have, e.getValue())) return false;
        }
        return randomFeatureSeeds == null || same(yaml.get("feature-seeds.generate-random-seeds-for-all"), randomFeatureSeeds);
    }

    /**
     * @param randomFeatureSeeds null leaves feature seeds alone; true/false writes the value
     * @param dryRun             only report what would change
     */
    public static Result apply(Path file, EngineSettings s, Boolean randomFeatureSeeds, boolean dryRun)
            throws IOException, InvalidConfigurationException {
        if (matches(file, s, randomFeatureSeeds)) return Result.UNCHANGED;
        if (dryRun) return Result.CHANGED;
        YamlConfiguration yaml = load(file);
        for (Map.Entry<String, Object> e : s.values().entrySet()) yaml.set(EngineSettings.PREFIX + e.getKey(), e.getValue());
        if (randomFeatureSeeds != null) yaml.set("feature-seeds.generate-random-seeds-for-all", randomFeatureSeeds);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path backup = file.resolveSibling(BACKUP);
        if (Files.isRegularFile(file) && Files.notExists(backup)) Files.copy(file, backup);
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), ".sentinelaxray-", ".tmp");
        try {
            Files.writeString(temp, yaml.saveToString());
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return Result.CHANGED;
    }

    private static YamlConfiguration load(Path file) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (Files.isRegularFile(file)) yaml.loadFromString(Files.readString(file));
        return yaml;
    }

    /** YAML numbers and lists come back as different Java types; compare by meaning. */
    static boolean same(Object have, Object want) {
        if (have == null) return false;
        if (want instanceof Number w && have instanceof Number h) return w.longValue() == h.longValue();
        if (want instanceof List<?> w && have instanceof List<?> h) {
            if (w.size() != h.size()) return false;
            for (int i = 0; i < w.size(); i++) {
                String a = String.valueOf(h.get(i)).toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "");
                if (!a.equals(String.valueOf(w.get(i)))) return false;
            }
            return true;
        }
        return Objects.equals(String.valueOf(have), String.valueOf(want));
    }
}
