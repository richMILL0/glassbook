package dev.glassbook.core.journal;

import dev.glassbook.core.model.Command;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32C;


public final class Journal implements Closeable {

    @FunctionalInterface
    public interface ReplayHandler {
        void onRecord(long seq, long timestamp, Command command);
    }

    public static final int MAX_RECORD_BYTES = 64 * 1024;
    private static final int HEADER_BYTES = 8;
    private static final String PREFIX = "wal-";
    private static final String SUFFIX = ".log";
    private static final System.Logger LOG = System.getLogger("dev.glassbook.journal");

    private final Path dir;
    private final long segmentMaxBytes;
    private final ByteBuffer writeBuffer = ByteBuffer.allocateDirect(1 << 20);
    private final ByteBuffer payload = ByteBuffer.allocate(MAX_RECORD_BYTES);
    private final CRC32C crc = new CRC32C();

    private FileChannel channel;
    private long segmentSize;
    private long lastSeq;

    private Journal(Path dir, long segmentMaxBytes) {
        this.dir = dir;
        this.segmentMaxBytes = segmentMaxBytes;
    }

    public static Journal open(Path dir, long segmentMaxBytes) throws IOException {
        Files.createDirectories(dir);
        Journal journal = new Journal(dir, segmentMaxBytes);
        List<Segment> segments = listSegments(dir);
        if (segments.isEmpty()) {
            journal.lastSeq = 0;
            return journal;
        }
        Segment last = segments.get(segments.size() - 1);
        byte[] bytes = Files.readAllBytes(last.path);
        Scan scan = scan(bytes, last.startSeq, null);
        if (scan.validBytes < bytes.length) {
            LOG.log(System.Logger.Level.WARNING, "journal {0}: truncating {1} bytes of torn tail",
                    last.path.getFileName(), bytes.length - scan.validBytes);
            try (FileChannel ch = FileChannel.open(last.path, StandardOpenOption.WRITE)) {
                ch.truncate(scan.validBytes);
                ch.force(true);
            }
        }

        journal.lastSeq = scan.count == 0 ? last.startSeq - 1 : scan.lastSeq;
        journal.channel = FileChannel.open(last.path, StandardOpenOption.WRITE);
        journal.channel.position(scan.validBytes);
        journal.segmentSize = scan.validBytes;
        return journal;
    }

    public long lastSeq() {
        return lastSeq;
    }

    // ------------------------------------------------------------------ Запись

    public void append(long seq, long timestamp, Command command) throws IOException {
        if (channel != null && seq != lastSeq + 1) {
            throw new IllegalStateException("journal sequence gap: last " + lastSeq + ", got " + seq);
        }

        if (channel == null && seq <= lastSeq) {
            throw new IllegalStateException("journal sequence went backwards: last " + lastSeq + ", got " + seq);
        }

        payload.clear();
        payload.putLong(seq);
        payload.putLong(timestamp);
        CommandCodec.encode(command, payload);
        int length = payload.position();
        crc.reset();
        crc.update(payload.array(), 0, length);

        int recordBytes = HEADER_BYTES + length;
        if (channel == null || (segmentSize > 0 && segmentSize + recordBytes > segmentMaxBytes)) {
            startSegment(seq);
        }
        if (writeBuffer.remaining() < recordBytes) {
            flushBuffer();
        }
        writeBuffer.putInt(length);
        writeBuffer.putInt((int) crc.getValue());
        writeBuffer.put(payload.array(), 0, length);
        segmentSize += recordBytes;
        lastSeq = seq;
    }

    public void commit(boolean fsync) throws IOException {
        if (channel == null) return;
        flushBuffer();
        if (fsync) channel.force(false);
    }

    public void rollSegment() throws IOException {
        if (channel == null) return;
        flushBuffer();
        channel.force(true);
        channel.close();
        channel = null;
        segmentSize = 0;
    }

    public void purgeBefore(long keepFromSeq) throws IOException {
        List<Segment> segments = listSegments(dir);
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (segments.get(i + 1).startSeq <= keepFromSeq) {
                Files.deleteIfExists(segments.get(i).path);
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            flushBuffer();
            channel.force(true);
            channel.close();
            channel = null;
        }
    }

    private void flushBuffer() throws IOException {
        writeBuffer.flip();
        while (writeBuffer.hasRemaining()) {
            channel.write(writeBuffer);
        }
        writeBuffer.clear();
    }

    private void startSegment(long firstSeq) throws IOException {
        if (channel != null) {
            flushBuffer();
            channel.force(true);
            channel.close();
        }
        Path path = dir.resolve(PREFIX + String.format("%020d", firstSeq) + SUFFIX);
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        segmentSize = 0;
        syncDirectory();
    }

    private void syncDirectory() {
        try (FileChannel d = FileChannel.open(dir, StandardOpenOption.READ)) {
            d.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {

        }
    }

    // ------------------------------------------------------------------ Чтение

    public static long replay(Path dir, long afterSeq, ReplayHandler handler) throws IOException {
        if (!Files.isDirectory(dir)) return afterSeq;
        List<Segment> segments = listSegments(dir);
        long expected = afterSeq + 1;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            boolean isLast = i == segments.size() - 1;
            if (!isLast && segments.get(i + 1).startSeq <= expected) continue; // nothing we need in here
            byte[] bytes = Files.readAllBytes(segment.path);
            long[] next = {expected};
            Scan scan = scan(bytes, segment.startSeq, (seq, ts, cmd) -> {
                if (seq < next[0]) return;
                if (seq != next[0]) {
                    throw new IllegalStateException("journal gap: expected seq " + next[0] + " but found " + seq
                            + " in " + segment.path.getFileName());
                }
                handler.onRecord(seq, ts, cmd);
                next[0]++;
            });
            if (scan.validBytes < bytes.length && !isLast) {
                throw new IOException("corrupt record in the middle of the journal: " + segment.path.getFileName());
            }
            expected = next[0];
        }
        return expected - 1;
    }

    private record Segment(long startSeq, Path path) {}

    private record Scan(long validBytes, long lastSeq, long count) {}

    private static List<Segment> listSegments(Path dir) throws IOException {
        List<Segment> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String digits = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
                out.add(new Segment(Long.parseLong(digits), p));
            }
        }
        out.sort(Comparator.comparingLong(Segment::startSeq));
        return out;
    }

    private static Scan scan(byte[] bytes, long startSeq, ReplayHandler handler) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        CRC32C crc = new CRC32C();
        long valid = 0;
        long lastSeq = startSeq - 1;
        long count = 0;
        while (buf.remaining() >= HEADER_BYTES) {
            int start = buf.position();
            int length = buf.getInt();
            int expectedCrc = buf.getInt();
            if (length < 16 || length > MAX_RECORD_BYTES || buf.remaining() < length) break;
            crc.reset();
            crc.update(bytes, buf.position(), length);
            if ((int) crc.getValue() != expectedCrc) break;
            ByteBuffer body = ByteBuffer.wrap(bytes, buf.position(), length).slice();
            long seq = body.getLong();
            long ts = body.getLong();
            Command command;
            try {
                command = CommandCodec.decode(body);
            } catch (RuntimeException e) {
                break;
            }
            buf.position(start + HEADER_BYTES + length);
            valid = buf.position();
            lastSeq = seq;
            count++;
            if (handler != null) handler.onRecord(seq, ts, command);
        }
        return new Scan(valid, lastSeq, count);
    }
}
