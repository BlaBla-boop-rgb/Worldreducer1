package de.syntaxium.worldtrim;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kern des Plugins: arbeitet direkt auf den .mca-Dateien und braucht keine Bukkit-API.
 *
 * Aufbau einer Region-Datei (32x32 Chunks):
 *  - Bytes 0..4095:    1024 Einträge à 4 Byte: 3 Byte Sektor-Offset, 1 Byte Sektor-Anzahl
 *  - Bytes 4096..8191: 1024 Zeitstempel
 *  - danach Chunk-Daten in 4-KB-Sektoren: 4 Byte Länge, 1 Byte Kompression, Daten
 */
public final class RegionTrimmer {

    private static final int SECTOR = 4096;
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    public record Settings(long minInhabitedTicks, int centerX, int centerZ, int protectRadius,
                           boolean protectBuilds, boolean includeExtras, boolean dryRun) {}

    /** Zähler für einen Durchlauf. */
    public static final class Stats {
        public long regionFiles, chunksTotal, chunksDeleted, chunksProtected, chunksUnreadable;
        public long chunksInhabited, chunksBuilt;
        public long bytesBefore, bytesAfter, filesDeleted;

        public void add(Stats o) {
            regionFiles += o.regionFiles; chunksTotal += o.chunksTotal; chunksDeleted += o.chunksDeleted;
            chunksProtected += o.chunksProtected; chunksUnreadable += o.chunksUnreadable;
            chunksInhabited += o.chunksInhabited; chunksBuilt += o.chunksBuilt;
            bytesBefore += o.bytesBefore; bytesAfter += o.bytesAfter; filesDeleted += o.filesDeleted;
        }

        public String summary() {
            return String.format("%d Chunks geprüft, %d löschbar (%.1f %%)%n"
                            + "Behalten: %d mit Bauten/Strukturen, %d viel besucht, %d im Schutzbereich, %d nicht lesbar%n"
                            + "Speicher: %s -> %s (spart %s), %d Dateien komplett leer",
                    chunksTotal, chunksDeleted, chunksTotal == 0 ? 0.0 : 100.0 * chunksDeleted / chunksTotal,
                    chunksBuilt, chunksInhabited, chunksProtected, chunksUnreadable,
                    human(bytesBefore), human(bytesAfter), human(bytesBefore - bytesAfter), filesDeleted);
        }
    }

    private final Settings settings;
    private final Logger log;
    private final ChunkInspector inspector;

    public RegionTrimmer(Settings settings, ChunkInspector inspector, Logger log) {
        this.settings = settings;
        this.inspector = inspector;
        this.log = log;
    }

    /** Alle Dimensions-Ordner einer Welt (Overworld, DIM-1 = Nether, DIM1 = End). */
    public static List<Path> dimensionFolders(Path worldFolder) {
        List<Path> result = new ArrayList<>();
        for (Path p : List.of(worldFolder, worldFolder.resolve("DIM-1"), worldFolder.resolve("DIM1"))) {
            if (Files.isDirectory(p.resolve("region"))) result.add(p);
        }
        return result;
    }

    /** Bereinigt (oder analysiert) einen Dimensions-Ordner, der "region/" enthält. */
    public Stats processDimension(Path dimFolder) throws IOException {
        Stats stats = new Stats();
        Path regionDir = dimFolder.resolve("region");
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(regionDir, "r.*.mca")) {
            ds.forEach(files::add);
        }
        int done = 0;
        for (Path file : files) {
            try {
                processRegion(dimFolder, file, stats);
            } catch (Exception e) {
                log.warning("Überspringe " + file.getFileName() + ": " + e.getMessage());
            }
            done++;
            if (done % 200 == 0) log.info("  ... " + done + "/" + files.size() + " Region-Dateien");
        }
        return stats;
    }

    private void processRegion(Path dimFolder, Path file, Stats stats) throws IOException {
        Matcher m = REGION_NAME.matcher(file.getFileName().toString());
        if (!m.matches()) return;
        int regionX = Integer.parseInt(m.group(1));
        int regionZ = Integer.parseInt(m.group(2));

        byte[] data = Files.readAllBytes(file);
        stats.regionFiles++;
        stats.bytesBefore += data.length;
        if (data.length < 2 * SECTOR) {
            stats.bytesAfter += data.length;
            return;
        }

        // Entities (Rüstungsständer, Itemrahmen, benannte Tiere ...) liegen in einer eigenen Datei
        byte[] entityData = null;
        Path entityFile = dimFolder.resolve("entities").resolve(file.getFileName());
        if (settings.protectBuilds() && Files.isRegularFile(entityFile)) entityData = Files.readAllBytes(entityFile);

        ByteBuffer header = ByteBuffer.wrap(data);
        BitSet delete = new BitSet(1024);
        boolean anyKept = false;

        for (int i = 0; i < 1024; i++) {
            int loc = header.getInt(i * 4);
            if (loc == 0) continue;
            stats.chunksTotal++;

            int chunkX = regionX * 32 + (i & 31);
            int chunkZ = regionZ * 32 + (i >> 5);
            if (isProtected(chunkX, chunkZ)) {
                stats.chunksProtected++;
                anyKept = true;
                continue;
            }

            Map<String, Object> root = readChunk(data, loc);
            long inhabited = root == null ? -1 : ChunkInspector.inhabitedTime(root);
            if (root == null || inhabited < 0) {
                stats.chunksUnreadable++; // im Zweifel behalten
                anyKept = true;
                continue;
            }
            if (inhabited >= settings.minInhabitedTicks()) {
                stats.chunksInhabited++;
                anyKept = true;
                continue;
            }
            if (settings.protectBuilds() && hasBuilds(root, entityData, i)) {
                stats.chunksBuilt++;
                anyKept = true;
                continue;
            }
            delete.set(i);
            stats.chunksDeleted++;
        }

        long newSize = compactedSize(data, delete, anyKept);
        stats.bytesAfter += newSize;
        if (newSize == 0) stats.filesDeleted++;

        if (settings.includeExtras()) {
            for (String extra : new String[]{"entities", "poi"}) {
                Path extraFile = dimFolder.resolve(extra).resolve(file.getFileName());
                if (!Files.isRegularFile(extraFile)) continue;
                byte[] extraData = Files.readAllBytes(extraFile);
                stats.bytesBefore += extraData.length;
                long extraNew = removeChunks(extraFile, extraData, delete);
                stats.bytesAfter += extraNew;
                if (extraNew == 0) stats.filesDeleted++;
            }
        }

        if (!delete.isEmpty()) {
            rewrite(file, data, delete);
        }
    }

    private boolean isProtected(int chunkX, int chunkZ) {
        int r = settings.protectRadius();
        if (r <= 0) return false;
        long dx = chunkX * 16L + 8 - settings.centerX();
        long dz = chunkZ * 16L + 8 - settings.centerZ();
        long reach = r + 12; // halbe Chunk-Diagonale, damit Chunks am Rand mitgeschützt sind
        return dx * dx + dz * dz <= reach * reach;
    }

    /** Gesetzte Blöcke im Chunk oder nicht-natürliche Entities in der entities-Datei? */
    private boolean hasBuilds(Map<String, Object> root, byte[] entityData, int index) {
        if (inspector.findBuiltBlock(root) != null) return true;
        if (entityData == null || entityData.length < 2 * SECTOR) return false;
        int loc = ByteBuffer.wrap(entityData).getInt(index * 4);
        if (loc == 0) return false;
        Map<String, Object> entities = readChunk(entityData, loc);
        if (entities == null) return true; // nicht lesbar -> sicherheitshalber behalten
        return ChunkInspector.findBuiltEntity(Nbt.list(entities.get("Entities"))) != null;
    }

    private static Map<String, Object> readChunk(byte[] data, int loc) {
        long offset = (long) (loc >>> 8) * SECTOR;
        if (offset < 2 * SECTOR || offset + 5 > data.length) return null;
        int o = (int) offset;
        int length = ByteBuffer.wrap(data).getInt(o);
        int compression = data[o + 4] & 0xFF;
        if ((compression & 0x80) != 0) return null; // ausgelagerter Riesen-Chunk (.mcc) -> behalten
        if (length <= 1 || o + 4L + length > data.length) return null;
        return Nbt.readChunk(data, o + 5, length - 1, compression);
    }

    /** Entfernt die markierten Chunks aus einer entities/poi-Datei. Gibt die neue Größe zurück. */
    private long removeChunks(Path file, byte[] data, BitSet delete) throws IOException {
        if (data.length < 2 * SECTOR || delete.isEmpty()) return data.length;
        ByteBuffer header = ByteBuffer.wrap(data);
        BitSet actual = new BitSet(1024);
        boolean anyKept = false;
        for (int i = 0; i < 1024; i++) {
            int loc = header.getInt(i * 4);
            if (loc == 0) continue;
            if (delete.get(i)) actual.set(i);
            else anyKept = true;
        }
        long size = compactedSize(data, actual, anyKept);
        if (!actual.isEmpty()) rewrite(file, data, actual);
        return size;
    }

    /** Berechnet die Dateigröße nach dem Neuschreiben (0 = Datei wird gelöscht). */
    private static long compactedSize(byte[] data, BitSet delete, boolean anyKept) {
        if (!anyKept) return 0;
        if (delete.isEmpty()) return data.length;
        ByteBuffer header = ByteBuffer.wrap(data);
        long size = 2L * SECTOR;
        for (int i = 0; i < 1024; i++) {
            int loc = header.getInt(i * 4);
            if (loc == 0 || delete.get(i)) continue;
            int sectors = chunkSectors(data, loc);
            if (sectors > 0) size += (long) sectors * SECTOR;
        }
        return size;
    }

    /** Anzahl Sektoren, die ein Chunk beim Neuschreiben belegt (0 = ungültig, wird verworfen). */
    private static int chunkSectors(byte[] data, int loc) {
        long offset = (long) (loc >>> 8) * SECTOR;
        if (offset < 2 * SECTOR || offset + 4 > data.length) return 0;
        int length = ByteBuffer.wrap(data).getInt((int) offset);
        if (length <= 0 || offset + 4L + length > data.length) return 0;
        int sectors = (4 + length + SECTOR - 1) / SECTOR;
        return sectors > 255 ? 0 : sectors;
    }

    /** Schreibt die Datei ohne die markierten Chunks neu (kompakt) oder löscht sie, wenn sie leer wird. */
    private void rewrite(Path file, byte[] data, BitSet delete) throws IOException {
        if (settings.dryRun()) return;

        ByteBuffer src = ByteBuffer.wrap(data);
        List<byte[]> parts = new ArrayList<>();
        int[] newLoc = new int[1024];
        int[] timestamps = new int[1024];
        int nextSector = 2;

        for (int i = 0; i < 1024; i++) {
            timestamps[i] = src.getInt(SECTOR + i * 4);
            int loc = src.getInt(i * 4);
            if (loc == 0 || delete.get(i)) continue;
            int sectors = chunkSectors(data, loc);
            if (sectors == 0) continue; // defekter Eintrag, wäre auch für den Server unlesbar
            int offset = (loc >>> 8) * SECTOR;
            int length = src.getInt(offset);
            byte[] chunk = new byte[sectors * SECTOR];
            System.arraycopy(data, offset, chunk, 0, 4 + length);
            parts.add(chunk);
            newLoc[i] = (nextSector << 8) | sectors;
            nextSector += sectors;
        }

        if (parts.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }

        ByteBuffer out = ByteBuffer.allocate(nextSector * SECTOR);
        for (int i = 0; i < 1024; i++) {
            out.putInt(i * 4, newLoc[i]);
            out.putInt(SECTOR + i * 4, newLoc[i] == 0 ? 0 : timestamps[i]);
        }
        out.position(2 * SECTOR);
        for (byte[] part : parts) out.put(part);

        Path tmp = file.resolveSibling(file.getFileName() + ".worldtrim.tmp");
        Files.write(tmp, out.array());
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int u = -1;
        do { v /= 1024; u++; } while (v >= 1024 && u < units.length - 1);
        return String.format("%.2f %s", v, units[u]);
    }
}
