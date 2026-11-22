package uz.sentinel.xray;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Finds the ore vein a block belongs to and whether that vein could be seen before the miner dug to it.
 * Pure Java (no Bukkit) so it can be unit tested.
 *
 * <p>A vein is "exposed" when one of its blocks touches open space (air, water, glass...) that the miner did not
 * create. Space the miner dug himself does not count: every ore you mine touches your own tunnel, so only
 * pre-existing openings (caves, other players' tunnels) make a vein visible. A miner who keeps finding unexposed
 * veins with very little digging in between is either extremely lucky or seeing through stone.</p>
 */
public final class VeinAnalyzer {
    /** Read-only view of the world around the mined block. */
    public interface Grid {
        /** Ore group of the block ("diamond", "debris"...), or null when the block is not a tracked ore. */
        String group(int x, int y, int z);

        /** True for blocks that let a player see the neighbouring block (air, liquids, glass, leaves...). */
        boolean open(int x, int y, int z);
    }

    /** Result of an analysis. {@code blocks} holds packed keys of every vein block found. */
    public record Vein(String group, int size, boolean exposed, Set<Long> blocks) {}

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private VeinAnalyzer() {}

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * Flood-fills the vein that contains (x, y, z) through all 26 neighbours, up to {@code limit} blocks.
     *
     * @param dug positions the miner broke recently; open space there does not expose the vein
     */
    public static Vein analyze(Grid grid, int x, int y, int z, Set<Long> dug, int limit) {
        String group = grid.group(x, y, z);
        if (group == null) return new Vein(null, 0, false, Set.of());
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> todo = new ArrayDeque<>();
        todo.add(new int[]{x, y, z});
        seen.add(key(x, y, z));
        boolean exposed = false;
        while (!todo.isEmpty() && seen.size() <= limit) {
            int[] b = todo.poll();
            for (int[] f : FACES) {
                int nx = b[0] + f[0], ny = b[1] + f[1], nz = b[2] + f[2];
                if (!exposed && grid.open(nx, ny, nz) && !dug.contains(key(nx, ny, nz))) exposed = true;
            }
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                int nx = b[0] + dx, ny = b[1] + dy, nz = b[2] + dz;
                long k = key(nx, ny, nz);
                if (seen.contains(k) || !group.equals(grid.group(nx, ny, nz))) continue;
                if (seen.size() >= limit) break;
                seen.add(k);
                todo.add(new int[]{nx, ny, nz});
            }
        }
        return new Vein(group, seen.size(), exposed, seen);
    }
}
