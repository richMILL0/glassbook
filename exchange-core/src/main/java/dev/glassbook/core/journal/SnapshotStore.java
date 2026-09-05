package dev.glassbook.core.journal;

import dev.glassbook.core.engine.Exchange;
import dev.glassbook.core.engine.ExchangeSnapshot;
import dev.glassbook.core.model.Instrument;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32C;

public final class SnapshotStore {

    private static final String PREFIX = "snapshot-";
    private static final String SUFFIX = ".bin";
    private static final System.Logger LOG = System.getLogger("dev.glassbook.snapshot");

    private final Path dir;

    public SnapshotStore(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    public long write(Exchange exchange) throws IOException {
        byte[] state = ExchangeSnapshot.toBytes(exchange);
        CRC32C crc = new CRC32C();
        crc.update(state, 0, state.length);
        ByteBuffer file = ByteBuffer.allocate(state.length + 4);
        file.put(state).putInt((int) crc.getValue()).flip();

        long seq = exchange.lastSeq();
        Path target = dir.resolve(PREFIX + String.format("%020d", seq) + SUFFIX);
        Path tmp = dir.resolve(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            while (file.hasRemaining()) ch.write(file);
            ch.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return seq;
    }

    public Exchange loadLatest(List<Instrument> config, int idempotencyCapacity) throws IOException {
        List<Path> files = list();
        for (int i = files.size() - 1; i >= 0; i--) {
            Path p = files.get(i);
            try {
                byte[] bytes = Files.readAllBytes(p);
                if (bytes.length < 8) throw new IOException("too short");
                CRC32C crc = new CRC32C();
                crc.update(bytes, 0, bytes.length - 4);
                int stored = ByteBuffer.wrap(bytes, bytes.length - 4, 4).getInt();
                if ((int) crc.getValue() != stored) throw new IOException("checksum mismatch");
                byte[] state = java.util.Arrays.copyOf(bytes, bytes.length - 4);
                return ExchangeSnapshot.fromBytes(state, config, idempotencyCapacity);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "skipping unusable snapshot " + p.getFileName() + ": " + e);
            }
        }
        return null;
    }

    public long prune(int keep) throws IOException {
        List<Path> files = list();
        for (int i = 0; i < files.size() - keep; i++) {
            Files.deleteIfExists(files.get(i));
        }
        files = list();
        return files.isEmpty() ? 0 : seqOf(files.get(0));
    }

    private List<Path> list() throws IOException {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            stream.forEach(out::add);
        }
        out.sort(Comparator.comparingLong(SnapshotStore::seqOf));
        return out;
    }

    private static long seqOf(Path p) {
        String name = p.getFileName().toString();
        return Long.parseLong(name.substring(PREFIX.length(), name.length() - SUFFIX.length()));
    }
}
