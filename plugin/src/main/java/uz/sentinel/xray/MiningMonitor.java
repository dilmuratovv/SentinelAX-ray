package uz.sentinel.xray;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import uz.sentinel.xray.api.XrayFlagEvent;

/**
 * Watches what players mine and flags those who keep reaching hidden ore veins with very little digging. This catches
 * what obfuscation alone cannot: ore simulators (seed crackers) and other bypasses. Main thread only. Alerts only;
 * it never blocks mining or punishes.
 */
final class MiningMonitor implements Listener {
    private static final int DUG_MEMORY = 256;

    private final Plugin plugin;
    private boolean enabled, ignoreCreative;
    private long windowMillis, cooldownMillis;
    private int minVeins, fillerMaxY;
    private double maxFillerPerVein;
    private Map<Material, String> valuable = new EnumMap<>(Material.class);
    private Set<Material> filler = EnumSet.noneOf(Material.class);

    private final Map<UUID, MiningStats> stats = new HashMap<>();
    private final Map<UUID, ArrayDeque<Long>> dugOrder = new HashMap<>();
    private final Map<UUID, Set<Long>> dug = new HashMap<>();
    private final Map<UUID, LinkedHashMap<Long, Long>> counted = new HashMap<>(); // world -> vein block -> time
    private final Map<UUID, LinkedHashMap<Long, Boolean>> placed = new HashMap<>(); // world -> placed ore blocks
    private long flags;

    MiningMonitor(Plugin plugin) { this.plugin = plugin; }

    void configure(ConfigurationSection c, Consumer<String> warn) {
        enabled = c.getBoolean("enabled", true);
        ignoreCreative = c.getBoolean("ignore-creative", true);
        windowMillis = Math.max(1, c.getLong("window-minutes", 30)) * 60_000L;
        cooldownMillis = Math.max(0, c.getLong("alert-cooldown-seconds", 120)) * 1000L;
        minVeins = Math.max(1, c.getInt("min-hidden-veins", 5));
        maxFillerPerVein = Math.max(1, c.getDouble("max-filler-per-vein", 40));
        fillerMaxY = c.getInt("overworld-filler-max-y", 64);
        Map<Material, String> v = new EnumMap<>(Material.class);
        ConfigurationSection groups = c.getConfigurationSection("valuable");
        if (groups != null) for (String group : groups.getKeys(false)) {
            for (String name : groups.getStringList(group)) {
                Material m = Material.matchMaterial(name);
                if (m == null || !m.isBlock()) warn.accept("Unknown block in detection.valuable." + group + ": " + name);
                else v.put(m, group.toLowerCase(Locale.ROOT));
            }
        }
        Set<Material> f = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("filler")) {
            Material m = Material.matchMaterial(name);
            if (m == null || !m.isBlock()) warn.accept("Unknown block in detection.filler: " + name); else f.add(m);
        }
        valuable = v;
        filler = f;
    }

    private static boolean eligible(World w) {
        return w.getEnvironment() == World.Environment.NORMAL || w.getEnvironment() == World.Environment.NETHER;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (!enabled || !valuable.containsKey(e.getBlockPlaced().getType())) return;
        Block b = e.getBlockPlaced();
        LinkedHashMap<Long, Boolean> set = placed.computeIfAbsent(b.getWorld().getUID(), k -> new LinkedHashMap<>());
        set.put(VeinAnalyzer.key(b.getX(), b.getY(), b.getZ()), Boolean.TRUE);
        if (set.size() > 50_000) set.remove(set.keySet().iterator().next());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void mine(BlockBreakEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR || (ignoreCreative && p.getGameMode() == GameMode.CREATIVE)) return;
        Block b = e.getBlock();
        World w = b.getWorld();
        if (!eligible(w)) return;
        long now = System.currentTimeMillis();
        long key = VeinAnalyzer.key(b.getX(), b.getY(), b.getZ());
        Material type = b.getType();
        MiningStats st = stats.computeIfAbsent(p.getUniqueId(), k -> new MiningStats());
        st.prune(now, windowMillis);
        if (filler.contains(type) && (w.getEnvironment() == World.Environment.NETHER || b.getY() <= fillerMaxY)) {
            st.filler(now);
        }
        String group = valuable.get(type);
        if (group != null && !isPlaced(w, key) && !isCounted(w, key, now)) {
            VeinAnalyzer.Vein vein = VeinAnalyzer.analyze(grid(w), b.getX(), b.getY(), b.getZ(), dugSet(p), 32);
            LinkedHashMap<Long, Long> c = counted.computeIfAbsent(w.getUID(), k -> new LinkedHashMap<>());
            for (long k : vein.blocks()) c.put(k, now);
            while (c.size() > 100_000) c.remove(c.keySet().iterator().next());
            st.vein(now, group, vein.exposed());
            int confidence = st.suspicion(minVeins, maxFillerPerVein);
            if (confidence > 0 && st.mayFlag(now, cooldownMillis)) {
                report(p, confidence, describe(st) + "; last: " + group + " vein of " + vein.size() + " at "
                        + w.getName() + " " + b.getX() + "," + b.getY() + "," + b.getZ(), false);
            }
        }
        remember(p, key);
    }

    @EventHandler public void quit(PlayerQuitEvent e) {
        dug.remove(e.getPlayer().getUniqueId());
        dugOrder.remove(e.getPlayer().getUniqueId());
    }

    /** Drops idle players and old vein marks (called every few minutes). */
    void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, MiningStats>> it = stats.entrySet().iterator();
        while (it.hasNext()) {
            MiningStats s = it.next().getValue();
            s.prune(now, windowMillis);
            if (s.isEmpty()) it.remove();
        }
        for (LinkedHashMap<Long, Long> c : counted.values()) c.values().removeIf(t -> now - t > windowMillis * 4);
    }

    private boolean isPlaced(World w, long key) {
        LinkedHashMap<Long, Boolean> set = placed.get(w.getUID());
        return set != null && set.remove(key) != null;
    }

    private boolean isCounted(World w, long key, long now) {
        LinkedHashMap<Long, Long> c = counted.get(w.getUID());
        Long t = c == null ? null : c.get(key);
        return t != null && now - t <= windowMillis * 4;
    }

    private Set<Long> dugSet(Player p) { return dug.getOrDefault(p.getUniqueId(), Set.of()); }

    private void remember(Player p, long key) {
        ArrayDeque<Long> order = dugOrder.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        Set<Long> set = dug.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (set.add(key)) order.add(key);
        while (order.size() > DUG_MEMORY) set.remove(order.poll());
    }

    private VeinAnalyzer.Grid grid(World w) {
        return new VeinAnalyzer.Grid() {
            @Override public String group(int x, int y, int z) {
                if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return null;
                return valuable.get(w.getType(x, y, z));
            }
            @Override public boolean open(int x, int y, int z) {
                if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return false;
                return !w.getType(x, y, z).isOccluding();
            }
        };
    }

    private String describe(MiningStats st) {
        return st.hiddenVeins() + " hidden veins with " + st.fillers() + " filler blocks mined in "
                + windowMillis / 60_000 + " min (" + st.exposedVeins() + " exposed veins)";
    }

    String stats(UUID player) {
        MiningStats st = stats.get(player);
        if (st == null) return "no mining recorded in the window";
        st.prune(System.currentTimeMillis(), windowMillis);
        int conf = st.suspicion(minVeins, maxFillerPerVein);
        return describe(st) + ", suspicion=" + conf + "%";
    }

    void report(Player p, int confidence, String detail, boolean test) {
        flags++;
        String text = "[SentinelAX-ray] " + p.getName() + " flagged xray-mining (" + confidence + "%)" + (test ? " [test]" : "") + ": " + detail;
        plugin.getLogger().info(text.substring("[SentinelAX-ray] ".length()));
        Component msg = Component.text(text, NamedTextColor.RED);
        for (Player online : Bukkit.getOnlinePlayers()) if (online.hasPermission("sentinelaxray.alerts")) online.sendMessage(msg);
        Bukkit.getPluginManager().callEvent(new XrayFlagEvent(p.getUniqueId(), p.getName(), confidence, detail, test));
    }

    boolean enabled() { return enabled; }
    long flags() { return flags; }
    int tracked() { return stats.size(); }
}
