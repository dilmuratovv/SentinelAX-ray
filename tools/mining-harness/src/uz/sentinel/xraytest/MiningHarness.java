package uz.sentinel.xraytest;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import uz.sentinel.xray.api.XrayFlagEvent;

/**
 * Test-only: three bot players mine through their own stone regions full of diamond veins at a vanilla-like
 * density, through Player#breakBlock (real BlockBreakEvents). Alice strip-mines blind, Bob always turns towards ore
 * he cannot see (x-ray), Carol does so half the time. Then Dave places TNT next to hidden ore and Eve at random.
 */
public final class MiningHarness extends JavaPlugin implements Listener {
    private static final int SIZE = 96, Y0 = -62, HEIGHT = 6;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final Map<String, List<String>> flags = new HashMap<>();
    private final List<String> results = new ArrayList<>();
    private World w;
    private final Random rnd = new Random(42);

    private final class Miner {
        final String name; final double cheat; final int ox;
        int x, z, dx = 1, dz = 0, straight, turns, steps;
        Miner(String name, double cheat, int ox) { this.name = name; this.cheat = cheat; this.ox = ox; x = ox + SIZE / 2; z = SIZE / 2; }
        Player p() { return Bukkit.getPlayerExact(name); }
    }

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            boolean started;
            public void run() {
                if (started) return;
                for (String n : List.of("Alice", "Bob", "Carol", "Dave", "Eve")) if (Bukkit.getPlayerExact(n) == null) return;
                started = true;
                Bukkit.getScheduler().runTaskLater(MiningHarness.this, MiningHarness.this::build, 40);
            }
        }, 20, 20);
    }

    @EventHandler public void flag(XrayFlagEvent e) {
        if (e.isTest()) return;
        flags.computeIfAbsent(e.getPlayerName(), k -> new ArrayList<>()).add(e.getConfidence() + "% " + e.getDetail());
        getLogger().info("FLAG " + e.getPlayerName() + " " + e.getConfidence() + "% " + e.getDetail());
    }

    private void build() {
        w = Bukkit.getWorlds().get(0);
        // spawn is where region 1 goes: move everybody onto open ground beside it (nobody may suffocate in the stone)
        for (String n : List.of("Alice", "Bob", "Carol", "Dave", "Eve")) {
            Player p = Bukkit.getPlayerExact(n);
            if (p != null) p.teleport(new Location(w, 48.5, -60, -20.5));
        }
        int veins = 0;
        for (int region = 0; region < 4; region++) {
            int ox = region * (SIZE + 16);
            for (int x = ox; x < ox + SIZE; x++) for (int z = 0; z < SIZE; z++) for (int y = Y0; y < Y0 + HEIGHT; y++) {
                w.getBlockAt(x, y, z).setType(Material.STONE, false);
            }
            // ~0.3 diamond veins of 2-6 blocks per chunk column in this 6 block high band
            for (int v = 0; v < 30; v++) {
                int vx = ox + 2 + rnd.nextInt(SIZE - 4), vz = 2 + rnd.nextInt(SIZE - 4), vy = Y0 + rnd.nextInt(HEIGHT);
                int n = 2 + rnd.nextInt(5);
                for (int i = 0; i < n; i++) {
                    Block b = w.getBlockAt(vx + rnd.nextInt(2), Math.min(Y0 + HEIGHT - 1, vy + rnd.nextInt(2)), vz + rnd.nextInt(2));
                    b.setType(Material.DIAMOND_ORE, false);
                }
                veins++;
            }
        }
        getLogger().info("BUILT " + veins + " veins");
        List<Miner> miners = List.of(new Miner("Alice", 0, 0), new Miner("Bob", 1.0, SIZE + 16), new Miner("Carol", 0.5, 2 * (SIZE + 16)));
        for (Miner m : miners) m.p().teleport(new Location(w, m.x + 0.5, Y0 + 1, m.z + 0.5));
        Bukkit.getScheduler().runTaskLater(this, () -> mine(miners, 0), 60);
    }

    /** One tunnel step per miner per tick: break the two blocks ahead, step in; at a decision point choose a way. */
    private void mine(List<Miner> miners, int tick) {
        boolean busy = false;
        for (Miner m : miners) {
            if (m.turns >= 120) continue;
            busy = true;
            Player p = m.p();
            if (p == null) continue;
            if (m.straight <= 0) {
                if (m.cheat > 0) digOut(m, p); // the x-ray miner digs out the vein it went for
                decide(m);
            }
            int nx = m.x + m.dx, nz = m.z + m.dz;
            if (nx < m.ox + 2 || nx >= m.ox + SIZE - 2 || nz < 2 || nz >= SIZE - 2) { m.dx = -m.dx; m.dz = -m.dz; m.straight = 0; continue; }
            p.teleport(new Location(w, m.x + 0.5, Y0 + 1, m.z + 0.5));
            for (int y = Y0 + 1; y <= Y0 + 2; y++) { Block b = w.getBlockAt(nx, y, nz); if (b.getType() != Material.AIR) p.breakBlock(b); }
            m.x = nx; m.z = nz; m.straight--; m.steps++;
            // ore that is now part of the tunnel wall is in sight: mine it like anybody would
            for (int[] d : DIRS) for (int y = Y0; y <= Y0 + 3; y++) {
                Block b = w.getBlockAt(m.x + d[0], y, m.z + d[1]);
                if (b.getType() == Material.DIAMOND_ORE) p.breakBlock(b);
            }
        }
        if (busy && tick < 20 * 600) { Bukkit.getScheduler().runTaskLater(this, () -> mine(miners, tick + 1), 1); return; }
        for (Miner m : miners) {
            List<String> f = flags.getOrDefault(m.name, List.of());
            results.add(m.name + " (cheat " + m.cheat + "): turns=" + m.turns + " steps=" + m.steps + " flags=" + f.size()
                    + (f.isEmpty() ? "" : " first=" + f.get(0)));
            getLogger().info("RESULT " + results.get(results.size() - 1));
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sxray stats " + m.name);
        }
        Bukkit.getScheduler().runTaskLater(this, this::explosions, 40);
    }

    private void digOut(Miner m, Player p) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = Y0; y < Y0 + HEIGHT; y++) {
            Block b = w.getBlockAt(m.x + dx, y, m.z + dz);
            if (b.getType() == Material.DIAMOND_ORE) p.breakBlock(b);
        }
    }

    private void decide(Miner m) {
        int[] best = null;
        List<int[]> options = new ArrayList<>();
        for (int[] d : DIRS) if (!(d[0] == -m.dx && d[1] == -m.dz)) options.add(d);
        if (rnd.nextDouble() < m.cheat) {
            int bestDist = Integer.MAX_VALUE;
            for (int[] d : options) {
                int dist = oreAhead(m.x, m.z, d);
                if (dist < bestDist) { bestDist = dist; best = d; }
            }
            if (bestDist == Integer.MAX_VALUE) best = null;
        }
        int[] d = best != null ? best : options.get(rnd.nextInt(options.size()));
        if (d[0] != m.dx || d[1] != m.dz) m.turns++;
        m.dx = d[0]; m.dz = d[1];
        // the x-ray miner tunnels straight to the ore it saw; everyone else digs a branch of random length
        m.straight = best != null ? Math.max(3, oreAhead(m.x, m.z, d)) : 6 + rnd.nextInt(9);
    }

    /** Distance to the nearest diamond within 32 blocks of a 5 wide band that way (what an x-ray shows), or MAX. */
    private int oreAhead(int x, int z, int[] d) {
        for (int step = 1; step <= 32; step++) for (int side = -2; side <= 2; side++) for (int y = Y0; y < Y0 + HEIGHT; y++) {
            int bx = x + d[0] * step + (d[1] != 0 ? side : 0), bz = z + d[1] * step + (d[0] != 0 ? side : 0);
            if (w.getBlockAt(bx, y, bz).getType() == Material.DIAMOND_ORE) return step;
        }
        return Integer.MAX_VALUE;
    }

    /** Dave blows up spots next to hidden diamonds, Eve random spots; both in region 4. */
    private void explosions() {
        int ox = 3 * (SIZE + 16);
        Player dave = Bukkit.getPlayerExact("Dave"), eve = Bukkit.getPlayerExact("Eve");
        List<Location> ore = new ArrayList<>();
        for (int x = ox + 4; x < ox + SIZE - 4; x++) for (int z = 4; z < SIZE - 4; z++) {
            if (w.getBlockAt(x, Y0 + 2, z).getType() == Material.DIAMOND_ORE) ore.add(new Location(w, x + 1.5, Y0 + 2, z + 0.5));
        }
        // standing on top of the stone region (not in the air: the server kicks floating players)
        dave.teleport(new Location(w, ox + 4.5, Y0 + HEIGHT, 4.5)); eve.teleport(new Location(w, ox + SIZE - 4.5, Y0 + HEIGHT, SIZE - 4.5));
        for (int i = 0; i < Math.min(14, ore.size()); i++) tnt(dave, ore.get(i), i * 6L);
        for (int i = 0; i < 30; i++) tnt(eve, new Location(w, ox + 8 + rnd.nextInt(SIZE - 16), Y0 + 2, 8 + rnd.nextInt(SIZE - 16)), i * 6L + 3);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (String n : List.of("Dave", "Eve")) {
                List<String> f = flags.getOrDefault(n, List.of());
                results.add(n + " (" + (n.equals("Dave") ? "TNT at hidden ore" : "TNT at random") + "): flags=" + f.size()
                        + (f.isEmpty() ? "" : " first=" + f.get(0)));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "sxray stats " + n);
                getLogger().info("RESULT " + results.get(results.size() - 1));
            }
            try {
                Files.createDirectories(getDataFolder().toPath());
                Files.write(getDataFolder().toPath().resolve("results.txt"), results);
            } catch (Exception e) { getLogger().warning(e.toString()); }
            getLogger().info("HARNESS DONE");
        }, 30 * 6 + 80);
    }

    private void tnt(Player p, Location at, long delay) {
        Bukkit.getScheduler().runTaskLater(this, () -> w.spawn(at, TNTPrimed.class, t -> { t.setSource(p); t.setFuseTicks(2); }), delay);
    }
}
