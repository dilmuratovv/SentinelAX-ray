package uz.sentinel.xray.los;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Per-player chunk packet copies, on stand-ins shaped like the server's classes (1.21.x) and records (26.x). */
class ChunkPacketsTest {
    /** Shaped like ClientboundLevelChunkPacketData: a class with final fields, built by a constructor we skip. */
    static class Data {
        private final Map<String, long[]> heightmaps;
        private final byte[] buffer;
        private final List<Object> blockEntitiesData;
        private final List<Object> extraPackets;
        private volatile boolean ready;
        Data(byte[] buffer, List<Object> entities) {
            this.heightmaps = Map.of("WORLD_SURFACE", new long[]{1, 2});
            this.buffer = buffer;
            this.blockEntitiesData = entities;
            this.extraPackets = List.of("extra");
            this.ready = true;
        }
    }

    /** Shaped like the 1.21.x packet class. */
    static class ClassPacket {
        private final int x, z;
        private final Data chunkData;
        private final Object lightData = "light";
        private volatile boolean ready = true;
        ClassPacket(int x, int z, Data d) { this.x = x; this.z = z; this.chunkData = d; }
    }

    /** Shaped like the 26.x packet record. */
    record RecordPacket(int x, int z, Data chunkData, Object lightData) {}

    private static Data data() {
        List<Object> entities = new ArrayList<>(List.of("chest", "sign"));
        return new Data(new byte[]{1, 2, 3}, entities);
    }

    @Test
    void classPacketIsCopiedAndTheOriginalKept() {
        Data d = data();
        ClassPacket shared = new ClassPacket(5, -7, d);
        byte[] mine = {9, 9, 9};
        List<Object> kept = List.of("sign");
        Object out = ChunkPackets.withData(shared, Map.of("buffer", mine, "blockEntitiesData", kept));
        assertNotNull(out);
        ClassPacket copy = assertInstanceOf(ClassPacket.class, out);
        assertNotSame(shared, copy);
        assertNotSame(d, copy.chunkData);
        assertSame(mine, copy.chunkData.buffer);
        assertSame(kept, copy.chunkData.blockEntitiesData);
        assertSame(d.heightmaps, copy.chunkData.heightmaps);
        assertSame(d.extraPackets, copy.chunkData.extraPackets);
        assertTrue(copy.chunkData.ready);
        assertEquals(5, copy.x);
        assertEquals(-7, copy.z);
        assertSame(shared.lightData, copy.lightData);
        assertTrue(copy.ready);
        // the packet other players get is untouched
        assertSame(d, shared.chunkData);
        assertArrayEquals(new byte[]{1, 2, 3}, d.buffer);
        assertEquals(List.of("chest", "sign"), d.blockEntitiesData);
    }

    @Test
    void recordPacketIsCopiedThroughItsConstructor() {
        Data d = data();
        RecordPacket shared = new RecordPacket(1, 2, d, "light");
        byte[] mine = {4};
        RecordPacket copy = assertInstanceOf(RecordPacket.class, ChunkPackets.withBuffer(shared, mine));
        assertNotSame(shared, copy);
        assertEquals(1, copy.x());
        assertEquals(2, copy.z());
        assertEquals("light", copy.lightData());
        assertSame(mine, copy.chunkData().buffer);
        assertSame(d.blockEntitiesData, copy.chunkData().blockEntitiesData);
        assertSame(d, shared.chunkData());
        assertArrayEquals(new byte[]{1, 2, 3}, d.buffer);
    }

    @Test
    void anUnknownShapeGivesNull() {
        assertNull(ChunkPackets.withBuffer("not a packet", new byte[0]));
        assertNull(ChunkPackets.withData(new ClassPacket(0, 0, data()), Map.of("noSuchField", 1)));
    }
}
