package uz.sentinel.xray;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import org.bukkit.World;

/** Reads what Paper's anti-xray engine is really doing in a running world, and where its config file lives. */
public final class EngineProbe {
    /** Running state: {@code mode} 0 = off, -1 = unknown. */
    public record Running(int mode, int maxBlockHeight, String detail) {
        public boolean active() { return mode > 0; }
    }

    private EngineProbe() {}

    /** The paper-world.yml Paper reads for this world (26.x: dimension folder; 1.21: world folder). */
    public static Path configFile(World world) {
        try {
            Method m = world.getClass().getMethod("getWorldPath");
            return ((Path) m.invoke(world)).resolve("paper-world.yml");
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // older Paper: Bukkit's world folder holds the per-world config
        }
        try {
            Method m = world.getClass().getMethod("getWorldFolder");
            return ((File) m.invoke(world)).toPath().resolve("paper-world.yml");
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("cannot locate the world folder of " + world.getName(), e);
        }
    }

    public static Running running(World world) {
        try {
            Object handle = world.getClass().getMethod("getHandle").invoke(world);
            Object controller = findField(handle.getClass(), "chunkPacketBlockController").get(handle);
            String type = controller.getClass().getSimpleName();
            if (!type.contains("AntiXray")) return new Running(0, 0, "off");
            Object mode = field(controller, "engineMode");
            int id = mode == null ? -1 : (int) mode.getClass().getMethod("getId").invoke(mode);
            Object height = field(controller, "maxBlockHeight");
            return new Running(id, height instanceof Integer h ? h : -1, "engine-mode " + id);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new Running(-1, -1, "unknown (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static Object field(Object owner, String name) {
        try {
            Field f = findField(owner.getClass(), name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
