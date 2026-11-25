package uz.sentinel.xray.los;

import org.bukkit.World;

/** Reads blocks for the raycaster, caching the chunk-loaded test. Unloaded chunks never hide anything. Main thread. */
public final class WorldOccluder implements Raycaster.Occluder {
    private final World world;
    private final boolean[] table;
    private final int minY, maxY;
    private int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
    private boolean loaded;

    public WorldOccluder(World world, boolean[] table) {
        this.world = world; this.table = table;
        this.minY = world.getMinHeight(); this.maxY = world.getMaxHeight();
    }

    @Override public boolean occludes(int x, int y, int z) {
        if (y < minY || y >= maxY) return false;
        int ccx = x >> 4, ccz = z >> 4;
        if (ccx != cx || ccz != cz) { cx = ccx; cz = ccz; loaded = world.isChunkLoaded(ccx, ccz); }
        if (!loaded) return false;
        int ord = world.getType(x, y, z).ordinal();
        return ord < table.length && table[ord];
    }
}
