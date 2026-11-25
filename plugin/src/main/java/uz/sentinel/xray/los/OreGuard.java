package uz.sentinel.xray.los;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.ArrayList;
import java.util.List;

/**
 * Outbound filter for one player (Netty thread): valuable ores arrive as their stand-in block in chunk data and block
 * updates until the main thread reveals them. Runs after Paper's own anti-xray (which already hid ores that touch no
 * air), so this layer is what hides ores in caves. Fails open: an unexpected packet shape is sent unchanged.
 */
final class OreGuard extends ChannelOutboundHandlerAdapter {
    static final String NAME = "sentinel_axray";

    private final OreView view;
    private final OreNms nms;
    volatile long rewritten, dropped;

    OreGuard(OreView view, OreNms nms) { this.view = view; this.nms = nms; }

    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Object out;
        try { out = view.bypass ? msg : filter(msg); } catch (Throwable t) { out = msg; }
        if (out == null) { dropped++; promise.trySuccess(); return; }
        super.write(ctx, out, promise);
    }

    private Object filter(Object msg) throws Exception {
        switch (msg.getClass().getSimpleName()) {
            case "ClientboundBundlePacket": {
                Object subs = OreNms.call(msg, "subPackets");
                if (!(subs instanceof Iterable<?> it)) return msg;
                List<Object> kept = new ArrayList<>();
                boolean changed = false;
                for (Object p : it) {
                    Object f = filter(p);
                    if (f != p) changed = true;
                    if (f != null) kept.add(f);
                }
                return changed ? nms.bundle(kept) : msg;
            }
            case "ClientboundLevelChunkWithLightPacket": chunk(msg); return msg;
            case "ClientboundBlockUpdatePacket": return blockUpdate(msg);
            case "ClientboundSectionBlocksUpdatePacket": return sectionUpdate(msg);
            default: return msg;
        }
    }

    private void chunk(Object packet) {
        Object data = OreNms.call(packet, "getChunkData", "chunkData");
        int cx = OreNms.i(packet, "getX", "x"), cz = OreNms.i(packet, "getZ", "z");
        if (data == null || cx == Integer.MIN_VALUE) return;
        if (!(OreNms.get(data, "buffer") instanceof byte[] buf)) return;
        long chunk = OreNms.chunkKey(cx, cz);
        view.dropChunk(chunk);
        ChunkFilter.Result r = ChunkFilter.hide(buf, nms::replacement, nms.stoneId());
        if (r == null || r.hidden().length == 0) return;
        rewritten++;
        int minY = view.minY;
        long[] keys = new long[r.hidden().length];
        for (int i = 0; i < keys.length; i++) {
            int h = r.hidden()[i], section = h >>> 12, local = h & 0xFFF;
            keys[i] = OreNms.key((cx << 4) + (local & 15), minY + (section << 4) + (local >>> 8), (cz << 4) + ((local >>> 4) & 15));
            view.hide(keys[i]);
        }
        view.fresh.add(keys);
    }

    private Object blockUpdate(Object packet) {
        Object pos = OreNms.call(packet, "getPos", "pos");
        Object state = OreNms.call(packet, "getBlockState", "blockState");
        if (pos == null || state == null) return packet;
        long key = OreNms.blockKey(pos);
        if (nms.isHidden(nms.stateId(state))) {
            if (view.isRevealed(key)) return packet;
            view.hide(key);
            view.fresh.add(new long[]{key});
            return null;
        }
        view.unhide(key);
        return packet;
    }

    /** Shared between players by Paper: never changed; held back for this player, other blocks resent singly. */
    private Object sectionUpdate(Object packet) {
        Object section = OreNms.get(packet, "sectionPos");
        Object positions = OreNms.get(packet, "positions");
        Object states = OreNms.get(packet, "states");
        if (section == null || !(positions instanceof short[] ps) || !(states instanceof Object[] ss)) return packet;
        int sx = OreNms.i(section, "getX", "x") << 4, sy = OreNms.i(section, "getY", "y") << 4, sz = OreNms.i(section, "getZ", "z") << 4;
        long[] keys = new long[ps.length];
        boolean[] ore = new boolean[ps.length];
        boolean drop = false;
        for (int i = 0; i < ps.length && i < ss.length; i++) {
            short s = ps[i];
            keys[i] = OreNms.key(sx + ((s >>> 8) & 15), sy + (s & 15), sz + ((s >>> 4) & 15));
            ore[i] = nms.isHidden(nms.stateId(ss[i]));
            if (ore[i] && !view.isRevealed(keys[i])) drop = true;
        }
        if (!drop) {
            for (int i = 0; i < keys.length; i++) if (!ore[i]) view.unhide(keys[i]);
            return packet;
        }
        List<Long> fresh = new ArrayList<>(), others = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            if (ore[i] && !view.isRevealed(keys[i])) { view.hide(keys[i]); fresh.add(keys[i]); }
            else { if (!ore[i]) view.unhide(keys[i]); others.add(keys[i]); }
        }
        view.fresh.add(fresh.stream().mapToLong(Long::longValue).toArray());
        if (!others.isEmpty()) view.resend.add(others.stream().mapToLong(Long::longValue).toArray());
        return null;
    }
}
