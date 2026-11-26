package uz.sentinel.xray.los;

import io.netty.channel.Channel;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Line-of-sight ore hiding (main thread side). Paper's engine only hides ores that touch no air; ores on cave walls,
 * in ravines and above its height limit are sent as they are, and an x-ray client shows every one of them. This layer
 * sends valuable ores as stone / deepslate / netherrack in every chunk and block update, and sends the real block
 * once the player has line of sight to one of its faces within {@code reveal-distance}. Revealed ores that have been
 * out of sight for a while go back to hidden. Works without a restart and at every height.
 */
public final class OreLayer {
    public static final List<String> DEFAULT_ORES = List.of("diamond_ore", "deepslate_diamond_ore", "ancient_debris",
            "emerald_ore", "deepslate_emerald_ore", "gold_ore", "deepslate_gold_ore", "nether_gold_ore",
            "lapis_ore", "deepslate_lapis_ore");

    private final Plugin plugin;
    private OreNms nms;
    private final Map<UUID, OreView> views = new ConcurrentHashMap<>();
    private final Map<UUID, OreGuard> guards = new ConcurrentHashMap<>();
    private boolean[] occluding = new boolean[0];
    private boolean enabled, debug;
    private double revealDistance;
    private int checkTicks, microsPerTick;
    private long rehideTicks, tick, reveals, rehides, rays, failedInjections;
    private Map<Material, Material> ores = new EnumMap<>(Material.class);

    public OreLayer(Plugin plugin) { this.plugin = plugin; }

    /** Stand-in for an ore: deepslate ores -> deepslate, nether ores -> netherrack, the rest -> stone. */
    public static Material standIn(Material ore) {
        String n = ore.name();
        if (n.startsWith("DEEPSLATE_")) return Material.DEEPSLATE;
        if (n.startsWith("NETHER_") || n.equals("ANCIENT_DEBRIS") || n.equals("GILDED_BLACKSTONE")) return Material.NETHERRACK;
        return Material.STONE;
    }

    /** Reads the {@code los} section. Returns false when the layer is off. */
    public boolean configure(ConfigurationSection c, java.util.function.Consumer<String> warn) {
        enabled = c.getBoolean("enabled", true);
        debug = c.getBoolean("debug", false);
        revealDistance = Math.max(8, Math.min(128, c.getDouble("reveal-distance", 64)));
        checkTicks = Math.max(1, Math.min(40, c.getInt("check-ticks", 3)));
        microsPerTick = Math.max(100, Math.min(20000, c.getInt("max-micros-per-tick", 2000)));
        rehideTicks = Math.max(0, c.getLong("rehide-after-ticks", 200));
        Map<Material, Material> m = new EnumMap<>(Material.class);
        for (String name : c.isList("blocks") ? c.getStringList("blocks") : DEFAULT_ORES) {
            Material ore = Material.matchMaterial(name.trim().toLowerCase(Locale.ROOT));
            if (ore == null || !ore.isBlock()) warn.accept("Unknown block in los.blocks: " + name);
            else m.put(ore, standIn(ore));
        }
        ores = m;
        Material[] all = Material.values();
        boolean[] table = new boolean[all.length];
        for (Material mat : all) {
            try { if (!mat.isLegacy() && mat.isBlock()) table[mat.ordinal()] = mat.isOccluding(); } catch (RuntimeException ignored) { }
        }
        occluding = table;
        return enabled;
    }

    /** Resolves the internals and hooks every online player. */
    public boolean start(java.util.function.Consumer<String> warn) {
        if (!enabled || ores.isEmpty()) return false;
        nms = OreNms.create(ores, warn);
        if (nms == null) { enabled = false; return false; }
        for (Player p : Bukkit.getOnlinePlayers()) inject(p);
        return true;
    }

    public boolean active() { return enabled && nms != null; }
    public boolean tracks(Material m) { return ores.containsKey(m); }

    // ------------------------------------------------------------------ connections

    public void inject(Player p) {
        if (!active()) return;
        OreView view = views.computeIfAbsent(p.getUniqueId(), OreView::new);
        refresh(p, view);
        if (!(OreNms.channel(p) instanceof Channel ch)) { failedInjections++; return; }
        OreGuard guard = new OreGuard(view, nms);
        guards.put(p.getUniqueId(), guard);
        ch.eventLoop().execute(() -> {
            try {
                if (ch.pipeline().get(OreGuard.NAME) != null) ch.pipeline().remove(OreGuard.NAME);
                if (ch.pipeline().get("packet_handler") != null) ch.pipeline().addBefore("packet_handler", OreGuard.NAME, guard);
                else ch.pipeline().addLast(OreGuard.NAME, guard);
            } catch (RuntimeException e) { failedInjections++; }
        });
    }

    public void remove(Player p) {
        views.remove(p.getUniqueId());
        guards.remove(p.getUniqueId());
        if (OreNms.channel(p) instanceof Channel ch && ch.isOpen()) ch.eventLoop().execute(() -> {
            try { if (ch.pipeline().get(OreGuard.NAME) != null) ch.pipeline().remove(OreGuard.NAME); } catch (RuntimeException ignored) { }
        });
    }

    /** Plugin disable: give every player the real ores back, then leave their connections. */
    public void releaseAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            OreView v = views.get(p.getUniqueId());
            if (v == null) continue;
            v.bypass = true;
            List<Long> all = new ArrayList<>();
            for (Set<Long> s : v.hidden.values()) all.addAll(s);
            for (long k : all) sendReal(p, v, k, false);
        }
        for (Player p : Bukkit.getOnlinePlayers()) remove(p);
    }

    private void refresh(Player p, OreView v) {
        World w = p.getWorld();
        if (!w.getUID().equals(v.world)) { v.clear(); v.world = w.getUID(); v.minY = w.getMinHeight(); }
        v.bypass = p.getGameMode() == GameMode.SPECTATOR || p.hasPermission("sentinelaxray.bypass");
    }

    public void resetView(Player p) { OreView v = views.get(p.getUniqueId()); if (v != null) { v.clear(); refresh(p, v); } }

    public void chunkUnloaded(Player p, int cx, int cz) {
        OreView v = views.get(p.getUniqueId());
        if (v != null) v.dropChunk(OreNms.chunkKey(cx, cz));
    }

    /** A block was broken: hidden ores right next to it may now be in sight of anyone nearby. */
    public void broken(Block b) {
        if (!active()) return;
        for (Player p : b.getWorld().getPlayers()) {
            OreView v = views.get(p.getUniqueId());
            if (v == null || p.getLocation().distanceSquared(b.getLocation()) > 64 * 64) continue;
            List<Long> near = new ArrayList<>();
            for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
                long k = OreNms.key(b.getX() + dx, b.getY() + dy, b.getZ() + dz);
                if (v.isHidden(k)) near.add(k);
            }
            if (!near.isEmpty()) v.fresh.add(near.stream().mapToLong(Long::longValue).toArray());
        }
    }

    /** True when the player has been sent the real block at this position (it was in their sight). */
    public boolean revealedTo(Player p, int x, int y, int z) {
        OreView v = views.get(p.getUniqueId());
        return v != null && v.isRevealed(OreNms.key(x, y, z));
    }

    /** True when this ore position is currently hidden from the player (they could not know it is there). */
    public boolean hiddenFrom(Player p, int x, int y, int z) {
        OreView v = views.get(p.getUniqueId());
        return v != null && v.isHidden(OreNms.key(x, y, z));
    }

    // ------------------------------------------------------------------ main tick

    public void tick(long now) {
        if (!active()) return;
        tick = now;
        long budget = System.nanoTime() + microsPerTick * 1000L;
        for (Player p : Bukkit.getOnlinePlayers()) {
            OreView v = views.get(p.getUniqueId());
            if (v == null) continue;
            if ((now + p.getEntityId()) % 20 == 0) refresh(p, v);
            for (long[] keys; (keys = v.resend.poll()) != null; ) for (long k : keys) sendReal(p, v, k, false);
            for (long[] keys; (keys = v.fresh.poll()) != null; ) for (long k : keys) if (v.isHidden(k)) consider(p, v, k);
            if ((now + p.getEntityId()) % checkTicks == 0 && System.nanoTime() < budget) scan(p, v, budget);
        }
    }

    private void scan(Player p, OreView v, long budget) {
        Location eye = p.getEyeLocation();
        int pcx = eye.getBlockX() >> 4, pcz = eye.getBlockZ() >> 4;
        int reach = (int) Math.ceil(revealDistance / 16.0) + 1;
        if (rehideTicks > 0) {
            for (Map.Entry<Long, Set<Long>> e : v.revealed.entrySet()) {
                for (Long k : e.getValue().toArray(new Long[0])) {
                    if (System.nanoTime() > budget) return;
                    int x = OreNms.keyX(k), y = OreNms.keyY(k), z = OreNms.keyZ(k);
                    if (visible(p, x, y, z)) { v.lastSeen.put(k, tick); continue; }
                    long seen = v.lastSeen.computeIfAbsent(k, kk -> tick);
                    double dx = x + 0.5 - eye.getX(), dy = y + 0.5 - eye.getY(), dz = z + 0.5 - eye.getZ();
                    if (tick - seen >= rehideTicks && dx * dx + dy * dy + dz * dz > 256) rehide(p, v, k);
                }
            }
        }
        for (Map.Entry<Long, Set<Long>> e : v.hidden.entrySet()) {
            long ck = e.getKey();
            if (Math.abs((int) (ck >> 32) - pcx) > reach || Math.abs((int) ck - pcz) > reach) continue;
            for (Long k : e.getValue().toArray(new Long[0])) {
                if (System.nanoTime() > budget) return;
                consider(p, v, k);
            }
        }
    }

    private void consider(Player p, OreView v, long key) {
        if (visible(p, OreNms.keyX(key), OreNms.keyY(key), OreNms.keyZ(key))) { sendReal(p, v, key, true); v.lastSeen.put(key, tick); }
    }

    private void sendReal(Player p, OreView v, long key, boolean reveal) {
        World w = p.getWorld();
        int x = OreNms.keyX(key), y = OreNms.keyY(key), z = OreNms.keyZ(key);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        Block b = w.getBlockAt(x, y, z);
        if (ores.containsKey(b.getType())) v.markRevealed(key); else v.unhide(key);
        if (reveal) reveals++;
        if (debug) plugin.getLogger().info("[debug] ore " + (reveal ? "reveal " : "send ") + b.getType() + " " + x + "," + y + "," + z + " to " + p.getName());
        p.sendBlockChange(b.getLocation(), b.getBlockData());
    }

    private void rehide(Player p, OreView v, long key) {
        World w = p.getWorld();
        int x = OreNms.keyX(key), y = OreNms.keyY(key), z = OreNms.keyZ(key);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;
        Block b = w.getBlockAt(x, y, z);
        v.lastSeen.remove(key);
        Material stand = ores.get(b.getType());
        if (stand == null) { v.unhide(key); return; }
        v.hide(key);
        rehides++;
        p.sendBlockChange(b.getLocation(), stand.createBlockData());
    }

    /** Line of sight from the eyes to a point just outside one of the block's exposed faces, within the distance. */
    boolean visible(Player p, int bx, int by, int bz) {
        Location eye = p.getEyeLocation();
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        double cx = bx + 0.5, cy = by + 0.5, cz = bz + 0.5;
        double d2 = (cx - ex) * (cx - ex) + (cy - ey) * (cy - ey) + (cz - ez) * (cz - ez);
        if (d2 > revealDistance * revealDistance) return false;
        WorldOccluder occ = new WorldOccluder(p.getWorld(), occluding);
        for (int face = 0; face < 6; face++) {
            int nx = face == 0 ? -1 : face == 1 ? 1 : 0, ny = face == 2 ? -1 : face == 3 ? 1 : 0, nz = face == 4 ? -1 : face == 5 ? 1 : 0;
            if (occ.occludes(bx + nx, by + ny, bz + nz)) continue;                     // face covered
            double fx = cx + nx * 0.55, fy = cy + ny * 0.55, fz = cz + nz * 0.55;
            if ((ex - fx) * nx + (ey - fy) * ny + (ez - fz) * nz <= 0) continue;      // face points away
            for (int k = 0; k < 5; k++) {
                double u = k == 1 ? -0.35 : k == 2 ? 0.35 : 0, w = k == 3 ? -0.35 : k == 4 ? 0.35 : 0;
                double px = fx, py = fy, pz = fz;
                if (nx != 0) { py += u; pz += w; } else if (ny != 0) { px += u; pz += w; } else { px += u; py += w; }
                rays++;
                if (Raycaster.clear(ex, ey, ez, px, py, pz, occ)) return true;
            }
        }
        return false;
    }

    /** Where chunk packets that are left alone are reported. */
    public static void setChunkWarning(java.util.function.Consumer<String> warn) { OreGuard.warn = warn; }

    public String status() {
        if (!active()) return "line-of-sight layer off";
        int hidden = 0, revealed = 0;
        long rewritten = 0, dropped = 0;
        for (OreView v : views.values()) {
            for (Set<Long> s : v.hidden.values()) hidden += s.size();
            for (Set<Long> s : v.revealed.values()) revealed += s.size();
        }
        for (OreGuard g : guards.values()) { rewritten += g.rewritten; dropped += g.dropped; }
        return "line-of-sight layer on (" + ores.size() + " ores, " + nms.hiddenStates() + " states): hidden=" + hidden
                + ", revealed=" + revealed + ", reveals=" + reveals + ", rehides=" + rehides + ", chunks rewritten="
                + rewritten + ", chunks unreadable/ambiguous/rejected=" + ChunkFilter.UNPARSED.sum() + "/" + ChunkFilter.AMBIGUOUS.sum() + "/" + ChunkFilter.REJECTED.sum()
                + ", updates held=" + dropped + ", rays=" + rays + (failedInjections > 0 ? ", injection failures=" + failedInjections : "");
    }
}
