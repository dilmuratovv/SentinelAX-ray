package uz.sentinel.xray.los;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Reflection over the server internals (Mojang names, which every supported Paper version runs with): block state
 * ids of the ores to hide and of their stand-ins, and generic accessors for packets (classes on 1.21.x, records on
 * 26.x). Network-thread safe.
 */
public final class OreNms {
    private static final Map<String, Method> methods = new ConcurrentHashMap<>();
    private static final Map<String, Field> fields = new ConcurrentHashMap<>();

    private final Method blockGetId;
    /** Block state id -> id of its stand-in (stone, deepslate, netherrack...), or -1 when the state is not hidden. */
    private final int[] replace;
    private final Constructor<?> bundleCtor;
    private final int hiddenStates;
    private int stoneId;

    private OreNms(Method blockGetId, int[] replace, Constructor<?> bundleCtor, int hiddenStates) {
        this.blockGetId = blockGetId; this.replace = replace; this.bundleCtor = bundleCtor; this.hiddenStates = hiddenStates;
    }

    /** @param targets ore -> stand-in block. Returns null (with a reason) when the internals are not as expected. */
    public static OreNms create(Map<Material, Material> targets, java.util.function.Consumer<String> warn) {
        try {
            String craft = Bukkit.getServer().getClass().getPackageName();
            Class<?> magic = Class.forName(craft + ".util.CraftMagicNumbers");
            Method getBlock = magic.getMethod("getBlock", Material.class);
            Class<?> blockClass = getBlock.getReturnType();
            ClassLoader cl = blockClass.getClassLoader();
            Class<?> stateClass = Class.forName("net.minecraft.world.level.block.state.BlockState", false, cl);
            Method getId = blockClass.getMethod("getId", stateClass);
            Method defaultState = blockClass.getMethod("defaultBlockState");
            Method definition = blockClass.getMethod("getStateDefinition");
            int max = 0;
            java.util.Map<Integer, Integer> map = new java.util.HashMap<>();
            for (Map.Entry<Material, Material> t : targets.entrySet()) {
                Object block = getBlock.invoke(null, t.getKey());
                Object standIn = getBlock.invoke(null, t.getValue());
                if (block == null || standIn == null) continue;
                int to = (int) getId.invoke(null, defaultState.invoke(standIn));
                Object def = definition.invoke(block);
                for (Object s : (List<?>) def.getClass().getMethod("getPossibleStates").invoke(def)) {
                    int id = (int) getId.invoke(null, s);
                    map.put(id, to);
                    max = Math.max(max, id);
                }
            }
            int[] replace = new int[max + 1];
            java.util.Arrays.fill(replace, -1);
            map.forEach((k, v) -> replace[k] = v);
            Class<?> bundle = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket", false, cl);
            OreNms nms = new OreNms(getId, replace, bundle.getConstructor(Iterable.class), map.size());
            nms.stoneId = (int) getId.invoke(null, defaultState.invoke(getBlock.invoke(null, Material.STONE)));
            return nms;
        } catch (Throwable t) {
            warn.accept("Line-of-sight ore hiding is off: server internals not found (" + t + ").");
            return null;
        }
    }

    public int hiddenStates() { return hiddenStates; }
    /** Stand-in used when another one's id is too long for the palette entry it replaces. */
    public int stoneId() { return stoneId; }
    public int replacement(int id) { return id >= 0 && id < replace.length ? replace[id] : -1; }
    public boolean isHidden(int id) { return replacement(id) >= 0; }

    int stateId(Object blockState) {
        try { return (int) blockGetId.invoke(null, blockState); } catch (ReflectiveOperationException e) { return -1; }
    }

    Object bundle(List<Object> packets) throws ReflectiveOperationException { return bundleCtor.newInstance(packets); }

    static Object call(Object o, String... names) {
        Class<?> c = o.getClass();
        for (String n : names) {
            String key = c.getName() + "#" + n;
            Method m = methods.get(key);
            if (m == null) {
                m = find(c, n);
                if (m == null) continue;
                methods.put(key, m);
            }
            try { return m.invoke(o); } catch (ReflectiveOperationException e) { return null; }
        }
        return null;
    }

    private static Method find(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try { Method m = k.getDeclaredMethod(name); m.setAccessible(true); return m; }
            catch (NoSuchMethodException | RuntimeException ignored) { }
        }
        try { return c.getMethod(name); } catch (NoSuchMethodException e) { return null; }
    }

    static Object get(Object o, String name) {
        Class<?> c = o.getClass();
        String key = c.getName() + "." + name;
        Field f = fields.get(key);
        if (f == null) {
            for (Class<?> k = c; k != null && f == null; k = k.getSuperclass()) {
                try { f = k.getDeclaredField(name); f.setAccessible(true); } catch (NoSuchFieldException | RuntimeException ignored) { f = null; }
            }
            if (f == null) return null;
            fields.put(key, f);
        }
        try { return f.get(o); } catch (IllegalAccessException e) { return null; }
    }

    static int i(Object o, String... names) {
        Object v = call(o, names);
        return v instanceof Number n ? n.intValue() : Integer.MIN_VALUE;
    }

    static long blockKey(Object pos) { return key(i(pos, "getX"), i(pos, "getY"), i(pos, "getZ")); }

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
    public static int keyX(long k) { return (int) (k >> 38); }
    public static int keyY(long k) { return (int) (k << 52 >> 52); }
    public static int keyZ(long k) { return (int) (k << 26 >> 38); }
    static long chunkKey(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    static Object channel(Player p) {
        Object handle = call(p, "getHandle");
        if (handle == null) return null;
        Object listener = get(handle, "connection");
        if (listener == null) return null;
        Object connection = get(listener, "connection");
        return connection == null ? null : get(connection, "channel");
    }
}
