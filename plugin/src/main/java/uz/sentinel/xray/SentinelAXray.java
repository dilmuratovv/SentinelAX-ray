package uz.sentinel.xray;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SentinelAX-ray: configures Paper's native anti-xray engine correctly for every world (it rewrites chunk packets
 * before they leave the server) and analyses mining to catch players who bypass obfuscation.
 */
public final class SentinelAXray extends JavaPlugin {
    private MiningMonitor monitor;
    private uz.sentinel.xray.los.OreLayer layer;
    private long ticks;

    @Override public void onEnable() {
        saveDefaultConfig();
        uz.sentinel.xray.los.ChunkFilter.expect(getServer().getMinecraftVersion());
        monitor = new MiningMonitor(this);
        layer = new uz.sentinel.xray.los.OreLayer(this);
        uz.sentinel.xray.los.OreLayer.setChunkWarning(getLogger()::warning);
        monitor.layer(layer);
        reloadSettings();
        ConfigurationSection los = getConfig().getConfigurationSection("los");
        if (layer.configure(los == null ? new YamlConfiguration() : los, getLogger()::warning) && layer.start(getLogger()::warning)) {
            getLogger().info("Line-of-sight ore hiding on: valuable ores in caves and above the engine's height are only sent"
                    + " once in sight (works without a restart).");
        }
        getServer().getPluginManager().registerEvents(monitor, this);
        getServer().getPluginManager().registerEvents(new LayerEvents(), this);
        getServer().getScheduler().runTaskTimer(this, () -> layer.tick(++ticks), 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, monitor::cleanup, 20L * 300, 20L * 300);
        // The 1.0.0 jar was called SentinelXray; both together double every alert and fight over paper-world.yml.
        for (String old : List.of("SentinelXray", "SentinelX-ray")) {
            if (getServer().getPluginManager().getPlugin(old) != null) {
                getLogger().severe("The old " + old + " plugin is also installed. Delete its jar from plugins/ and restart;"
                        + " running both duplicates alerts and both rewrite paper-world.yml.");
            }
        }
        if (getConfig().getBoolean("engine.auto-apply", true)) {
            List<String> lines = applyAll(false);
            lines.forEach(getLogger()::info);
        }
        pending.clear();
        for (World w : getServer().getWorlds()) {
            if (!eligible(w)) continue;
            String line = worldStatus(w);
            if (needsAction(line)) { pending.add(line); getLogger().warning("Anti-xray NOT active: " + line); }
            else getLogger().info(line);
        }
        if (!pending.isEmpty()) {
            getLogger().warning("Ores are still sent to x-ray clients. "
                    + (getConfig().getBoolean("engine.auto-apply", true) ? "Restart Paper once to activate the engine."
                    : "Run /sxray apply, then restart Paper."));
            getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler public void join(org.bukkit.event.player.PlayerJoinEvent e) {
                    if (pending.isEmpty() || !e.getPlayer().hasPermission("sentinelaxray.admin")) return;
                    e.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                            "[SentinelAX-ray] Anti-xray is not active yet: " + String.join(" | ", pending)
                                    + ". See /sxray status.", net.kyori.adventure.text.format.NamedTextColor.RED));
                }
            }, this);
        }
    }

    @Override public void onDisable() {
        if (layer != null) layer.releaseAll();
    }

    /** Hooks players into the line-of-sight layer and keeps its view in step with their chunks and worlds. */
    private final class LayerEvents implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
        public void join(org.bukkit.event.player.PlayerJoinEvent e) { layer.inject(e.getPlayer()); }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void quit(org.bukkit.event.player.PlayerQuitEvent e) { layer.remove(e.getPlayer()); }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void unload(io.papermc.paper.event.packet.PlayerChunkUnloadEvent e) {
            layer.chunkUnloaded(e.getPlayer(), e.getChunk().getX(), e.getChunk().getZ());
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void world(org.bukkit.event.player.PlayerChangedWorldEvent e) { layer.resetView(e.getPlayer()); }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void respawn(org.bukkit.event.player.PlayerRespawnEvent e) { layer.resetView(e.getPlayer()); }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void broken(org.bukkit.event.block.BlockBreakEvent e) { layer.broken(e.getBlock()); }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void exploded(org.bukkit.event.entity.EntityExplodeEvent e) { for (var b : e.blockList()) layer.broken(b); }

        /** Worlds loaded later (Multiverse...) get the engine settings too; they apply at the next restart. */
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void worldLoad(org.bukkit.event.world.WorldLoadEvent e) {
            if (!eligible(e.getWorld()) || !getConfig().getBoolean("engine.auto-apply", true)) return;
            try {
                Path path = EngineProbe.configFile(e.getWorld());
                if (WorldConfigWriter.apply(path, desired(e.getWorld()), featureSeeds(), false) != WorldConfigWriter.Result.UNCHANGED) {
                    getLogger().info(e.getWorld().getName() + ": engine settings written to " + path + " (active after a restart;"
                            + " the line-of-sight layer already protects it)");
                }
            } catch (IOException | InvalidConfigurationException | RuntimeException ex) {
                getLogger().warning("Cannot configure " + e.getWorld().getName() + ": " + ex);
            }
        }
    }

    /** Worlds whose engine is not running as configured, as status lines (shown to admins on join). */
    private final List<String> pending = new ArrayList<>();

    static boolean needsAction(String statusLine) {
        return statusLine.contains("not applied") || statusLine.contains("restart Paper") || statusLine.contains("unreadable");
    }

    private void reloadSettings() {
        reloadConfig();
        var bundled = getResource("config.yml");
        if (bundled != null) {
            getConfig().setDefaults(YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(bundled, java.nio.charset.StandardCharsets.UTF_8)));
        }
        ConfigurationSection d = getConfig().getConfigurationSection("detection");
        monitor.configure(d == null ? new YamlConfiguration() : d, getLogger()::warning);
    }

    static boolean eligible(World w) {
        return w.getEnvironment() == World.Environment.NORMAL || w.getEnvironment() == World.Environment.NETHER;
    }

    private EngineSettings desired(World w) {
        return desired(w.getEnvironment() == World.Environment.NETHER ? "nether" : "overworld");
    }

    /**
     * With the line-of-sight layer on, the engine runs in mode 1: it hides ores that touch no air and the layer hides
     * the rest, so an x-ray client sees no valuable ore at all. Modes 2/3 would add fake ores by the thousand, which
     * the layer would then have to track one by one.
     */
    private EngineSettings desired(String dimension) {
        ConfigurationSection engine = getConfig().getConfigurationSection("engine");
        if (engine == null) engine = new YamlConfiguration();
        EngineSettings s = EngineSettings.from(engine, dimension, getLogger()::warning);
        if (layer != null && layer.active() && s.mode() != 1) {
            s = new EngineSettings(1, s.maxBlockHeight(), s.updateRadius(), s.lavaObscures(), s.usePermission(), s.hiddenBlocks(), s.replacementBlocks());
        }
        return s;
    }

    private Boolean featureSeeds() {
        String v = getConfig().getString("engine.random-feature-seeds", "unchanged");
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** One line per eligible world: running engine, file state, whether a restart is pending. */
    String worldStatus(World w) {
        EngineProbe.Running run = EngineProbe.running(w);
        EngineSettings want = desired(w);
        String file;
        try {
            Path path = EngineProbe.configFile(w);
            boolean ok = WorldConfigWriter.matches(path, want, featureSeeds());
            boolean live = run.mode() == want.mode() && (run.maxBlockHeight() < 0 || run.maxBlockHeight() == want.maxBlockHeight());
            file = !ok ? "config not applied (/sxray apply)"
                    : run.mode() < 0 ? "config applied (running engine could not be read on this Paper build)"
                    : live ? "config applied" : "config applied, restart Paper to activate";
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            file = "config unreadable: " + e.getMessage();
        }
        return w.getName() + ": running " + run.detail()
                + (run.maxBlockHeight() > 0 ? " up to y=" + run.maxBlockHeight() : "") + "; " + file;
    }

    /** Read-only status for SentinelGuard. */
    public String getProtectionStatus() {
        List<String> parts = new ArrayList<>();
        for (World w : getServer().getWorlds()) {
            if (!eligible(w)) continue;
            EngineProbe.Running run = EngineProbe.running(w);
            parts.add(w.getName() + "=" + (run.active() ? "mode " + run.mode() : run.mode() == 0 ? "off" : "unknown"));
        }
        return String.join(", ", parts) + "; " + layer.status() + "; mining analysis " + (monitor.enabled() ? "on" : "off")
                + " (" + monitor.flags() + " flags)";
    }

    private List<String> applyAll(boolean dryRun) {
        List<String> out = new ArrayList<>();
        // Defaults for every world without its own setting: worlds created later and Multiverse worlds.
        try {
            Path defaults = Path.of("config", "paper-world-defaults.yml");
            if (java.nio.file.Files.isRegularFile(defaults)) {
                WorldConfigWriter.Result r = WorldConfigWriter.apply(defaults, desired("overworld"), featureSeeds(), dryRun);
                out.add("defaults: " + (r == WorldConfigWriter.Result.UNCHANGED ? "already up to date"
                        : dryRun ? "would change " + defaults : "written to " + defaults + " (restart Paper to activate)"));
            }
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            out.add("defaults: failed - " + e.getMessage());
        }
        for (World w : getServer().getWorlds()) {
            if (!eligible(w)) continue;
            try {
                Path path = EngineProbe.configFile(w);
                WorldConfigWriter.Result r = WorldConfigWriter.apply(path, desired(w), featureSeeds(), dryRun);
                out.add(w.getName() + ": " + (r == WorldConfigWriter.Result.UNCHANGED ? "already up to date"
                        : dryRun ? "would change " + path : "written to " + path + " (restart Paper to activate)"));
            } catch (IOException | InvalidConfigurationException | RuntimeException e) {
                out.add(w.getName() + ": failed - " + e.getMessage());
                getLogger().warning("Cannot configure " + w.getName() + ": " + e);
            }
        }
        return out;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                pending.clear();
                for (World w : getServer().getWorlds()) {
                    if (!eligible(w)) continue;
                    String line = worldStatus(w);
                    if (needsAction(line)) pending.add(line);
                    sender.sendMessage(line);
                }
                sender.sendMessage(layer.status());
                sender.sendMessage("mining analysis: " + (monitor.enabled() ? "on" : "off") + ", players tracked: "
                        + monitor.tracked() + ", flags: " + monitor.flags());
            }
            case "apply" -> {
                boolean dry = args.length > 1 && args[1].equalsIgnoreCase("--dry-run");
                applyAll(dry).forEach(sender::sendMessage);
            }
            case "reload" -> {
                reloadSettings();
                sender.sendMessage("SentinelAX-ray configuration reloaded. Engine changes still need /sxray apply and a restart.");
            }
            case "stats" -> {
                if (args.length != 2) { sender.sendMessage("Usage: /" + label + " stats <player>"); return true; }
                var target = Bukkit.getOfflinePlayer(args[1]);
                sender.sendMessage(args[1] + ": " + monitor.stats(target.getUniqueId()));
            }
            case "testflag" -> {
                Player p = args.length == 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (p == null) { sender.sendMessage("Usage: /" + label + " testflag <online player>"); return true; }
                monitor.report(p, 50, "test flag from " + sender.getName(), true);
            }
            default -> { return false; }
        }
        return true;
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("status", "apply", "reload", "stats", "testflag")) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("apply")) return List.of("--dry-run");
        return null;
    }
}
