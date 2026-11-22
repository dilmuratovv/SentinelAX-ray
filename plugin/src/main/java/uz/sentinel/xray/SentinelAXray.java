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

    @Override public void onEnable() {
        saveDefaultConfig();
        monitor = new MiningMonitor(this);
        reloadSettings();
        getServer().getPluginManager().registerEvents(monitor, this);
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
        ConfigurationSection engine = getConfig().getConfigurationSection("engine");
        if (engine == null) engine = new YamlConfiguration();
        return EngineSettings.from(engine, w.getEnvironment() == World.Environment.NETHER ? "nether" : "overworld",
                getLogger()::warning);
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
        return String.join(", ", parts) + "; mining analysis " + (monitor.enabled() ? "on" : "off")
                + " (" + monitor.flags() + " flags)";
    }

    private List<String> applyAll(boolean dryRun) {
        List<String> out = new ArrayList<>();
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
