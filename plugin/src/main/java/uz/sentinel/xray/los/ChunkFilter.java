package uz.sentinel.xray.los;

import java.util.function.IntUnaryOperator;

/**
 * Rewrites the block sections of a chunk packet in place so that chosen block states (chests, barrels, shulker
 * boxes, spawners...) arrive as something else (air, or stone for full blocks so a wall shows no hole). Pure Java, so it is unit tested against every section format.
 *
 * <p>Base finders read storage blocks straight from the chunk data the moment it arrives, so hiding them later with
 * block changes would be too late. The section format differs between versions (a fluid count since 26.1, the data
 * length prefix dropped in 1.21.5), so the layout is not guessed from a version number: every known layout is tried
 * and only one that reads the buffer exactly to its end is used. Nothing changes length: a palette entry is replaced
 * by an over-long VarInt encoding of the air id with the same byte count (the client's VarInt reader accepts it), and
 * values in a direct (global) palette are replaced bit by bit.</p>
 */
public final class ChunkFilter {
    private ChunkFilter() {}

    /** Layout found and the hidden positions: {@code sectionIndex << 12 | y << 8 | z << 4 | x}. */
    public record Result(int sections, boolean fluidCount, boolean lengthPrefix, int[] hidden) {}

    private static final boolean[][] LAYOUTS = {{false, true}, {false, false}, {true, false}, {true, true}};

    /** Hides every id for which {@code replace} returns another id (it returns -1 for ids to keep). */
    public static Result hideWhere(byte[] buf, java.util.function.IntPredicate storage, int air) {
        return hide(buf, id -> storage.test(id) ? air : -1);
    }

    public static Result hide(byte[] buf, IntUnaryOperator replace) { return hide(buf, replace, 0); }

    /**
     * Returns null when the buffer matches no known layout (it is then left untouched). {@code fallback} is written
     * when a stand-in's id does not fit the bytes of the palette entry it replaces (it must be small, e.g. stone).
     */
    public static Result hide(byte[] buf, IntUnaryOperator replace, int fallback) {
        for (boolean[] l : LAYOUTS) {
            int sections = walk(buf, l[0], l[1], null, null, 0);
            if (sections > 0) {
                IntList hidden = new IntList();
                walk(buf, l[0], l[1], replace, hidden, fallback);
                return new Result(sections, l[0], l[1], hidden.toArray());
            }
        }
        return null;
    }

    /** Counts the sections of one layout (-1 when it does not fit); with {@code storage} set it also rewrites. */
    private static int walk(byte[] buf, boolean fluid, boolean prefix, IntUnaryOperator storage, IntList hidden, int fallback) {
        int[] pos = {0};
        int sections = 0;
        try {
            while (pos[0] < buf.length) {
                if (sections > 64) return -1;
                pos[0] += fluid ? 4 : 2;
                if (pos[0] > buf.length) return -1;
                if (!container(buf, pos, prefix, 4096, 8, storage, hidden, sections, fallback)) return -1;
                if (!container(buf, pos, prefix, 64, 3, null, null, sections, fallback)) return -1;
                sections++;
            }
        } catch (IndexOutOfBoundsException e) {
            return -1;
        }
        return pos[0] == buf.length ? sections : -1;
    }

    private static boolean container(byte[] buf, int[] pos, boolean prefix, int entries, int maxPaletteBits,
                                     IntUnaryOperator storage, IntList hidden, int section, int fallback) {
        int bits = buf[pos[0]++] & 0xFF;
        if (bits > 32) return false;
        if (bits == 0) {
            int at = pos[0];
            int id = readVarInt(buf, pos);
            if (id < 0) return false;
            if (prefix && readVarInt(buf, pos) != 0) return false;
            int to = storage == null ? -1 : storage.applyAsInt(id);
            int len = pos[0] - at - (prefix ? 1 : 0);
            if (to >= 0) {
                writeOverlong(buf, at, len, fits(to, len) ? to : fallback);
                for (int i = 0; i < entries; i++) hidden.add(section << 12 | i);
            }
            return true;
        }
        boolean[] hiddenEntry = null;
        int[] remap = null; // palette index of a hidden entry -> index of an entry that already is its stand-in
        boolean direct = bits > maxPaletteBits;
        if (!direct) {
            int size = readVarInt(buf, pos);
            if (size <= 0 || size > (1 << bits) + 1) return false;
            int[] ids = new int[size], at = new int[size], len = new int[size];
            for (int i = 0; i < size; i++) {
                at[i] = pos[0];
                ids[i] = readVarInt(buf, pos);
                if (ids[i] < 0) return false;
                len[i] = pos[0] - at[i];
            }
            if (storage != null) for (int i = 0; i < size; i++) {
                int to = storage.applyAsInt(ids[i]);
                if (to < 0) continue;
                if (hiddenEntry == null) { hiddenEntry = new boolean[size]; remap = new int[size]; java.util.Arrays.fill(remap, -1); }
                hiddenEntry[i] = true;
                // The section often has the stand-in already (deepslate around deepslate ore): point the blocks at
                // that entry, so even a stand-in id longer than the ore's entry is exact.
                for (int j = 0; j < size; j++) if (j != i && ids[j] == to) { remap[i] = j; break; }
                writeOverlong(buf, at[i], len[i], fits(to, len[i]) ? to : fallback);
            }
        }
        int perLong = 64 / bits;
        int longs = (entries + perLong - 1) / perLong;
        if (prefix) {
            int n = readVarInt(buf, pos);
            if (n != longs) return false;
        }
        int start = pos[0];
        pos[0] += longs * 8;
        if (pos[0] > buf.length) return false;
        if (storage == null) return true;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < entries; i++) {
            int li = start + (i / perLong) * 8;
            int shift = (i % perLong) * bits;
            long word = readLong(buf, li);
            int value = (int) ((word >>> shift) & mask);
            if (direct) {
                int to = storage.applyAsInt(value);
                if (to >= 0 && to <= mask) {
                    writeLong(buf, li, (word & ~(mask << shift)) | ((long) to & mask) << shift);
                    hidden.add(section << 12 | i);
                }
            } else if (hiddenEntry != null && value < hiddenEntry.length && hiddenEntry[value]) {
                if (remap[value] >= 0) writeLong(buf, li, (word & ~(mask << shift)) | ((long) remap[value] & mask) << shift);
                hidden.add(section << 12 | i);
            }
        }
        return true;
    }

    /** Whether {@code value} can be written as a VarInt of {@code length} bytes. */
    static boolean fits(int value, int length) {
        return length >= 5 || (value >>> (7 * length)) == 0;
    }

    static int readVarInt(byte[] buf, int[] pos) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            byte b = buf[pos[0]++];
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        return -1;
    }

    /** Writes {@code value} as a VarInt of exactly {@code length} bytes (padding with continuation bits). */
    static void writeOverlong(byte[] buf, int at, int length, int value) {
        for (int i = 0; i < length; i++) {
            int part = value & 0x7F;
            value >>>= 7;
            buf[at + i] = (byte) (i < length - 1 ? part | 0x80 : part);
        }
    }

    private static long readLong(byte[] b, int i) {
        long v = 0;
        for (int k = 0; k < 8; k++) v = v << 8 | (b[i + k] & 0xFF);
        return v;
    }

    private static void writeLong(byte[] b, int i, long v) {
        for (int k = 7; k >= 0; k--) { b[i + k] = (byte) v; v >>>= 8; }
    }

    /** Minimal growable int list (no boxing on the network thread). */
    static final class IntList {
        private int[] a = new int[16];
        private int n;
        void add(int v) { if (n == a.length) a = java.util.Arrays.copyOf(a, n * 2); a[n++] = v; }
        int[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
