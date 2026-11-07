package de.syntaxium.worldtrim;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Minimaler NBT-Leser. Compounds werden zu Map, Listen zu List, Zahlen zu Number, Strings zu String.
 * Große Arrays (Blockdaten, Lichtwerte) werden übersprungen, weil sie für die Prüfung nicht gebraucht werden.
 */
public final class Nbt {

    private static final Constructor<?> LZ4_STREAM = findLz4();

    private Nbt() {}

    private static Constructor<?> findLz4() {
        try {
            // Wird vom Minecraft-Server selbst mitgeliefert (für region-file-compression=lz4)
            return Class.forName("net.jpountz.lz4.LZ4BlockInputStream").getConstructor(InputStream.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Liest einen Chunk aus einer Region-Datei.
     * @param compression 1 = GZip, 2 = Zlib, 3 = keine, 4 = LZ4
     * @return Wurzel-Compound oder null, wenn nicht lesbar
     */
    public static Map<String, Object> readChunk(byte[] data, int offset, int length, int compression) {
        try (InputStream raw = new ByteArrayInputStream(data, offset, length);
             InputStream in = decompress(raw, compression)) {
            if (in == null) return null;
            DataInputStream dis = new DataInputStream(in);
            if (dis.readUnsignedByte() != 10) return null;
            dis.skipNBytes(dis.readUnsignedShort());
            return readCompound(dis);
        } catch (Exception e) {
            return null;
        }
    }

    private static InputStream decompress(InputStream raw, int compression) throws Exception {
        return switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            case 4 -> LZ4_STREAM == null ? null : (InputStream) LZ4_STREAM.newInstance(raw);
            default -> null;
        };
    }

    private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
        Map<String, Object> map = new HashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return map;
            String name = in.readUTF();
            map.put(name, readPayload(in, type));
        }
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: in.skipNBytes(in.readInt()); return null;
            case 8: return in.readUTF();
            case 9: {
                int elemType = in.readUnsignedByte();
                int count = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, Math.min(count, 4096)));
                for (int i = 0; i < count; i++) list.add(readPayload(in, elemType));
                return list;
            }
            case 10: return readCompound(in);
            case 11: in.skipNBytes(4L * in.readInt()); return null;
            case 12: in.skipNBytes(8L * in.readInt()); return null;
            default: throw new IOException("Unbekannter NBT-Typ " + type);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : null;
    }
}
