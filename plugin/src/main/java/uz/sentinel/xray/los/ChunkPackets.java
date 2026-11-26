package uz.sentinel.xray.los;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player copies of chunk packets. Paper sends one {@code ClientboundLevelChunkWithLightPacket} object to every
 * player watching a refreshed chunk ({@code World.refreshChunk}, used by WorldEdit and many other plugins), on each
 * player's own Netty thread. A filter that wrote its rewrite into that shared object would hand the first player's
 * view to everyone else (who then never get the hidden blocks revealed) while other threads are still encoding it.
 * So the rewritten bytes go into a copy of the packet and of its chunk data; the original is never touched.
 *
 * <p>Both shapes are handled: classes (1.21.x, and the chunk data on every version) are copied field by field into
 * an instance made without running a constructor; records (the 26.x packet) through their canonical constructor.</p>
 */
final class ChunkPackets {
    private ChunkPackets() {}

    private static final Map<Class<?>, Field[]> FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>> RECORDS = new ConcurrentHashMap<>();
    private static final Object UNSAFE;
    private static final Method ALLOCATE;

    static {
        Object unsafe = null;
        Method allocate = null;
        try {
            Class<?> c = Class.forName("sun.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = f.get(null);
            allocate = c.getMethod("allocateInstance", Class.class);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        UNSAFE = unsafe;
        ALLOCATE = allocate;
    }

    /**
     * A copy of {@code packet} whose chunk data carries {@code buffer}; everything else is shared with the original
     * (it is never written). Null when the packet does not have the expected shape.
     */
    static Object withBuffer(Object packet, byte[] buffer) { return withData(packet, Map.of("buffer", buffer)); }

    /** Like {@link #withBuffer}, with any fields of the chunk data replaced (buffer, blockEntitiesData). */
    static Object withData(Object packet, Map<String, Object> changes) {
        try {
            Object data = OreNms.get(packet, "chunkData");
            if (data == null) return null;
            Object dataCopy = copy(data, changes);
            return dataCopy == null ? null : copy(packet, Map.of("chunkData", dataCopy));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** A shallow copy of {@code o} with the named instance fields replaced; null if one of them does not exist. */
    static Object copy(Object o, Map<String, Object> changes) throws ReflectiveOperationException {
        Class<?> c = o.getClass();
        if (c.isRecord()) {
            RecordComponent[] rc = c.getRecordComponents();
            Object[] args = new Object[rc.length];
            int found = 0;
            for (int i = 0; i < rc.length; i++) {
                if (changes.containsKey(rc[i].getName())) { args[i] = changes.get(rc[i].getName()); found++; continue; }
                Method accessor = rc[i].getAccessor();
                accessor.setAccessible(true);
                args[i] = accessor.invoke(o);
            }
            if (found != changes.size()) return null;
            Constructor<?> ctor = RECORDS.computeIfAbsent(c, k -> {
                Class<?>[] types = new Class<?>[rc.length];
                for (int i = 0; i < rc.length; i++) types[i] = rc[i].getType();
                try {
                    Constructor<?> k2 = k.getDeclaredConstructor(types);
                    k2.setAccessible(true);
                    return k2;
                } catch (NoSuchMethodException e) {
                    throw new IllegalStateException(e);
                }
            });
            return ctor.newInstance(args);
        }
        if (ALLOCATE == null) return null;
        Field[] fields = FIELDS.computeIfAbsent(c, ChunkPackets::instanceFields);
        int found = 0;
        for (Field f : fields) if (changes.containsKey(f.getName())) found++;
        if (found != changes.size()) return null;
        Object copy = ALLOCATE.invoke(UNSAFE, c);
        for (Field f : fields) f.set(copy, changes.containsKey(f.getName()) ? changes.get(f.getName()) : f.get(o));
        return copy;
    }

    private static Field[] instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out.toArray(new Field[0]);
    }
}
