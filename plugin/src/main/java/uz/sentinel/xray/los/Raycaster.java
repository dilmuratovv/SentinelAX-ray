package uz.sentinel.xray.los;

/**
 * Exact voxel traversal (Amanatides &amp; Woo) between two points. Pure Java so it can be unit tested.
 * The voxel that contains the start point is skipped (the viewer's own head), every other voxel the segment
 * touches, including the one holding the end point, is tested.
 */
public final class Raycaster {
    /** Answers whether the block at the given coordinates hides what is behind it. */
    @FunctionalInterface
    public interface Occluder {
        boolean occludes(int x, int y, int z);
    }

    private Raycaster() {}

    /** True when no occluding voxel lies on the segment from (sx,sy,sz) to (ex,ey,ez). */
    public static boolean clear(double sx, double sy, double sz, double ex, double ey, double ez, Occluder occluder) {
        int x = floor(sx), y = floor(sy), z = floor(sz);
        final int tx = floor(ex), ty = floor(ey), tz = floor(ez);
        final double dx = ex - sx, dy = ey - sy, dz = ez - sz;
        final int stepX = Double.compare(dx, 0), stepY = Double.compare(dy, 0), stepZ = Double.compare(dz, 0);
        final double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        final double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        final double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? x + 1 - sx : sx - x) * tDeltaX;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? y + 1 - sy : sy - y) * tDeltaY;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? z + 1 - sz : sz - z) * tDeltaZ;
        int guard = Math.abs(tx - x) + Math.abs(ty - y) + Math.abs(tz - z) + 1;
        while (guard-- > 0) {
            if (x == tx && y == ty && z == tz) return true;
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX; }
                else { z += stepZ; tMaxZ += tDeltaZ; }
            } else if (tMaxY < tMaxZ) { y += stepY; tMaxY += tDeltaY; }
            else { z += stepZ; tMaxZ += tDeltaZ; }
            if (occluder.occludes(x, y, z)) return false;
        }
        return true;
    }

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
