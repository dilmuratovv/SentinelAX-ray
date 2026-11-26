package uz.sentinel.aetest;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only: /refreshchunk x z resends a chunk the way WorldEdit and many plugins do (one shared packet). */
public final class RefreshProbe extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2) return false;
        World w = Bukkit.getWorlds().get(0);
        int x = Integer.parseInt(args[0]), z = Integer.parseInt(args[1]);
        boolean ok = w.refreshChunk(x, z);
        getLogger().info("refreshed " + x + " " + z + " ok=" + ok + " watchers=" + w.getPlayersSeeingChunk(x, z).size());
        return true;
    }
}
