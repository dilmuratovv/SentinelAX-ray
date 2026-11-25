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
    /** Direction choices (tunnel turns, TNT spots) per player, kept longer than the filler window. */
    private final Map<UUID, ChoiceTest> choices = new HashMap<>();
    private final Map<UUID, int[]> heading = new HashMap<>();      // dx, dz of the current tunnel, and turn x, z
    private final Map<Long, UUID> bedUsers = new HashMap<>();       // bed / anchor block -> player who used it
    private final Map<Long, Long> bedTimes = new HashMap<>();
    private uz.sentinel.xray.los.OreLayer layer;
    private long choiceWindowMillis;
    private int choiceMinHits, lookAhead;
    private double choiceMaxP;
    private final Map<UUID, ArrayDeque<Long>> dugOrder = new HashMap<>();
    private final Map<UUID, Set<Long>> dug = new HashMap<>();
    private final Map<UUID, LinkedHashMap<Long, Long>> counted = new HashMap<>(); // world -> vein block -> time
    private final Map<UUID, LinkedHashMap<Long, Boolean>> placed = new HashMap<>(); // world -> placed ore blocks
    private long flags;

    MiningMonitor(Plugin plugin) { this.plugin = plugin; }

    void layer(uz.sentinel.xray.los.OreLayer layer) { this.layer = layer; }

    void configure(ConfigurationSection c, Consumer<String> warn) {
        enabled = c.getBoolean("enabled", true);
        ignoreCreative = c.getBoolean("ignore-creative", true);
        windowMillis = Math.max(1, c.getLong("window-minutes", 30)) * 60_000L;
        cooldownMillis = Math.max(0, c.getLong("alert-cooldown-seconds", 120)) * 1000L;
        minVeins = Math.max(1, c.getInt("min-hidden-veins", 5));
        maxFillerPerVein = Math.max(1, c.getDouble("max-filler-per-vein", 40));
        fillerMaxY = c.getInt("overworld-filler-max-y", 64);
        choiceWindowMillis = Math.max(1, c.getLong("choices.window-minutes", 120)) * 60_000L;
        choiceMinHits = Math.max(2, c.getInt("choices.min-hits", 4));
        choiceMaxP = Math.max(1e-12, Math.min(0.01, c.getDouble("choices.max-p", 1e-4)));
        lookAhead = Math.max(4, Math.min(32, c.getInt("choices.look-ahead", 16)));
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

    /** True where both filler and veins are counted: anywhere in the Nether, up to {@code fillerMaxY} in the overworld. */
    static boolean measurable(boolean nether, int y, int fillerMaxY) {
        return nether || y <= fillerMaxY;
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
        boolean measurable = measurable(w.getEnvironment() == World.Environment.NETHER, b.getY(), fillerMaxY);
        if (filler.contains(type) && measurable) st.filler(now);
        String group = valuable.get(type);
        // Veins are only judged where the filler around them is counted too; otherwise a legit miner in the
        // mountains (emeralds above the filler height) would show 0 filler per vein and look like x-ray.
        if (group != null && measurable && !isPlaced(w, key) && !isCounted(w, key, now)) {
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
        if (measurable && (filler.contains(type) || group != null)) turn(p, b, now);
        remember(p, key);
    }

    // ------------------------------------------------------------------ direction choices

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * A block dug straight beside the player's feet tells which way they tunnel. When that way changes (not back the
     * way they came), it is a choice: does the chosen way hold valuable ore the player could not see, and how many of
     * the other ways (straight on, left, right) do?
     */
    private void turn(Player p, Block b, long now) {
        var feet = p.getLocation();
        int fx = feet.getBlockX(), fz = feet.getBlockZ();
        int rx = b.getX() - fx, rz = b.getZ() - fz, ry = b.getY() - feet.getBlockY();
        if (Math.abs(rx) + Math.abs(rz) != 1 || ry < -1 || ry > 2) return; // not tunnelling from where they stand
        int[] h = heading.get(p.getUniqueId());
        if (h == null) { heading.put(p.getUniqueId(), new int[]{rx, rz, fx, fz}); return; }
        if (h[0] == rx && h[1] == rz) return;                       // same way on
        boolean back = h[0] == -rx && h[1] == -rz;
        boolean closeToLast = Math.abs(fx - h[2]) + Math.abs(fz - h[3]) < 2; // a one-block zig-zag is no new choice
        heading.put(p.getUniqueId(), new int[]{rx, rz, fx, fz});
        if (back || closeToLast) return;                            // turning round, or zig-zag at one spot
        World w = b.getWorld();
        int y = feet.getBlockY();
        int options = 0, withOre = 0;
        boolean hit = false;
        for (int[] d : DIRS) {
            if (d[0] == -h[0] && d[1] == -h[1]) continue;           // where they came from is no choice
            options++;
            boolean ore = hiddenOreAhead(p, w, fx, y, fz, d[0], d[1]);
            if (ore) withOre++;
            if (d[0] == rx && d[1] == rz) hit = ore;
        }
        ChoiceTest t = choices.computeIfAbsent(p.getUniqueId(), k -> new ChoiceTest());
        t.prune(now, choiceWindowMillis);
        t.choice(now, hit, options, withOre);
        judgeChoices(p, t, now, "tunnel turns");
    }

    /**
     * Hidden valuable ore within {@code lookAhead} blocks going (dx, dz) from (x, y, z), in a band 5 wide and 4 high
     * around the tunnel: where a tunnel that way would lead someone who sees ore.
     */
    private boolean hiddenOreAhead(Player p, World w, int x, int y, int z, int dx, int dz) {
        for (int step = 1; step <= lookAhead; step++) {
            for (int side = -2; side <= 2; side++) {
                int bx = x + dx * step + (dz != 0 ? side : 0), bz = z + dz * step + (dx != 0 ? side : 0);
                for (int dy = -1; dy <= 2; dy++) if (hiddenOre(p, w, bx, y + dy, bz)) return true;
            }
        }
        return false;
    }

    /** A valuable ore the player could not have seen: hidden by the line-of-sight layer, or touching no open space. */
    private boolean hiddenOre(Player p, World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return false;
        if (!valuable.containsKey(w.getType(x, y, z))) return false;
        // With the layer: ore the player was never shown (hidden in their client, or in a chunk not sent to them yet).
        if (layer != null && layer.active()) return !layer.revealedTo(p, x, y, z);
        for (int[] f : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            int nx = x + f[0], ny = y + f[1], nz = z + f[2];
            if (ny >= w.getMinHeight() && ny < w.getMaxHeight() && !w.getType(nx, ny, nz).isOccluding()) return false;
        }
        return true;
    }

    private void judgeChoices(Player p, ChoiceTest t, long now, String what) {
        int confidence = t.suspicion(choiceMinHits, choiceMaxP);
        MiningStats st = stats.computeIfAbsent(p.getUniqueId(), k -> new MiningStats());
        if (confidence > 0 && st.mayFlag(now, cooldownMillis)) {
            report(p, confidence, String.format(Locale.ROOT, "%s: chose hidden ore %d of %d times where blind choices"
                    + " would find it %.1f times (p=%.1e)", what, t.hits(), t.count(), t.expected(), t.pValue()), false);
        }
    }

    /** Beds and respawn anchors blow up in the Nether: remember who used one, the explosion follows. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void use(org.bukkit.event.player.PlayerInteractEvent e) {
        if (!enabled || e.getClickedBlock() == null || e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        Material m = e.getClickedBlock().getType();
        if (!m.name().endsWith("_BED") && m != Material.RESPAWN_ANCHOR) return;
        Block b = e.getClickedBlock();
        long k = VeinAnalyzer.key(b.getX(), b.getY(), b.getZ());
        bedUsers.put(k, e.getPlayer().getUniqueId());
        bedTimes.put(k, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void tnt(org.bukkit.event.entity.EntityExplodeEvent e) {
        if (!enabled || !(e.getEntity() instanceof org.bukkit.entity.TNTPrimed tnt) || !(tnt.getSource() instanceof Player p)) return;
        explosion(p, e.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void bed(org.bukkit.event.block.BlockExplodeEvent e) {
        if (!enabled) return;
        Block b = e.getBlock();
        long k = VeinAnalyzer.key(b.getX(), b.getY(), b.getZ());
        UUID who = bedUsers.remove(k);
        Long at = bedTimes.remove(k);
        Player p = who == null ? null : Bukkit.getPlayer(who);
        if (p != null && at != null && System.currentTimeMillis() - at < 3000) explosion(p, b.getLocation());
    }

    /**
     * Where to blow something up is a choice too (Nether debris): the spot and four spots 6 blocks around it; each
     * holds hidden ore when some lies within 2 blocks of it.
     */
    private void explosion(Player p, org.bukkit.Location at) {
        World w = at.getWorld();
        if (!eligible(w) || p.getGameMode() == GameMode.SPECTATOR || (ignoreCreative && p.getGameMode() == GameMode.CREATIVE)) return;
        int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
        boolean hit = oreAround(p, w, x, y, z);
        int withOre = hit ? 1 : 0;
        for (int[] d : DIRS) if (oreAround(p, w, x + d[0] * 6, y, z + d[1] * 6)) withOre++;
        long now = System.currentTimeMillis();
        ChoiceTest t = choices.computeIfAbsent(p.getUniqueId(), k -> new ChoiceTest());
        t.prune(now, choiceWindowMillis);
        t.choice(now, hit, 5, withOre);
        judgeChoices(p, t, now, "explosions");
    }

    private boolean oreAround(Player p, World w, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            if (hiddenOre(p, w, x + dx, y + dy, z + dz)) return true;
        }
        return false;
    }

    /** The miner's own tunnel stays theirs after a rejoin (it is no "foreign opening" then); only the heading resets. */
    @EventHandler public void quit(PlayerQuitEvent e) { heading.remove(e.getPlayer().getUniqueId()); }

    String choices(UUID player) {
        ChoiceTest t = choices.get(player);
        if (t == null) return "no direction choices recorded";
        t.prune(System.currentTimeMillis(), choiceWindowMillis);
        return String.format(Locale.ROOT, "%d choices, %d toward hidden ore (blind expectation %.1f, p=%.1e)", t.count(), t.hits(), t.expected(), t.pValue());
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
        choices.values().forEach(t -> t.prune(now, choiceWindowMillis));
        choices.values().removeIf(ChoiceTest::isEmpty);
        bedTimes.entrySet().removeIf(e -> now - e.getValue() > 10_000);
        bedUsers.keySet().retainAll(bedTimes.keySet());
        // tunnels of players who are gone and idle are forgotten (they were kept over a rejoin)
        dug.keySet().removeIf(id -> Bukkit.getPlayer(id) == null && !stats.containsKey(id));
        dugOrder.keySet().retainAll(dug.keySet());
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
        if (st == null) return "no mining recorded in the window; " + choices(player);
        st.prune(System.currentTimeMillis(), windowMillis);
        int conf = st.suspicion(minVeins, maxFillerPerVein);
        return describe(st) + ", suspicion=" + conf + "%; " + choices(player);
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
