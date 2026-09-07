package dev.glassbook.core;

import dev.glassbook.core.journal.Journal;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTest {

    private static Command command(int i) {
        return switch (i % 4) {
            case 0 -> new Command.PlaceOrder(i, 1, "client-" + i, Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 1_000 + i, 5);
            case 1 -> new Command.CancelOrder(i, i * 3L);
            case 2 -> new Command.Deposit(i, 0, 10L * i);
            default -> new Command.SetHalted(1, i % 8 == 3);
        };
    }

    private static void write(Path dir, int from, int to, long segmentBytes) throws IOException {
        try (Journal j = Journal.open(dir, segmentBytes)) {
            for (int i = from; i <= to; i++) j.append(i, 10_000 + i, command(i));
            j.commit(true);
        }
    }

    private static List<Long> replay(Path dir, long after) throws IOException {
        List<Long> seqs = new ArrayList<>();
        Journal.replay(dir, after, (seq, ts, cmd) -> {
            assertEquals(command((int) seq), cmd, "command " + seq);
            assertEquals(10_000 + seq, ts);
            seqs.add(seq);
        });
        return seqs;
    }

    private static List<Path> segments(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("wal-")).sorted().toList();
        }
    }

    @Test
    @DisplayName("records survive a close/open cycle and replay in order, from any starting point")
    void roundTrip(@TempDir Path dir) throws IOException {
        write(dir, 1, 100, 1 << 20);

        assertEquals(100, replay(dir, 0).size());
        List<Long> tail = replay(dir, 60);
        assertEquals(40, tail.size());
        assertEquals(61L, tail.get(0).longValue());
        assertTrue(replay(dir, 100).isEmpty());
    }

    @Test
    @DisplayName("reopening continues the sequence; a gap is refused")
    void reopenAndAppend(@TempDir Path dir) throws IOException {
        write(dir, 1, 10, 1 << 20);
        try (Journal j = Journal.open(dir, 1 << 20)) {
            assertEquals(10, j.lastSeq());
            assertThrows(IllegalStateException.class, () -> j.append(12, 0, command(12)));
            j.append(11, 10_011, command(11));
            j.commit(true);
        }
        assertEquals(11, replay(dir, 0).size());
    }

    @Test
    @DisplayName("small segments rotate, replay crosses them, purge deletes only fully covered ones")
    void segmentsRotateAndPurge(@TempDir Path dir) throws IOException {
        write(dir, 1, 200, 600);
        List<Path> before = segments(dir);
        assertTrue(before.size() > 5, "expected many segments, got " + before.size());
        assertEquals(200, replay(dir, 0).size());

        try (Journal j = Journal.open(dir, 600)) {
            j.purgeBefore(150);
        }
        List<Path> after = segments(dir);
        assertTrue(after.size() < before.size());
        List<Long> seqs = replay(dir, 149);
        assertEquals(51, seqs.size());
        assertEquals(150L, seqs.get(0).longValue());
    }

    @Test
    @DisplayName("replaying with a hole in the history fails instead of producing a wrong state")
    void gapDetected(@TempDir Path dir) throws IOException {
        write(dir, 1, 200, 600);
        List<Path> files = segments(dir);
        Files.delete(files.get(2));
        assertThrows(IllegalStateException.class, () -> replay(dir, 0));
    }

    @Test
    @DisplayName("a torn write at the end (crash mid-record) is cut off and the journal keeps working")
    void tornTailIsRepaired(@TempDir Path dir) throws IOException {
        write(dir, 1, 50, 1 << 20);
        Path file = segments(dir).get(0);
        long size = Files.size(file);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size - 7);              // the last record is now incomplete
        }

        try (Journal j = Journal.open(dir, 1 << 20)) {
            assertEquals(49, j.lastSeq());
            j.append(50, 10_050, command(50));
            j.commit(true);
        }
        assertEquals(50, replay(dir, 0).size());
    }

    @Test
    @DisplayName("garbage after the last good record is discarded")
    void garbageTail(@TempDir Path dir) throws IOException {
        write(dir, 1, 20, 1 << 20);
        Path file = segments(dir).get(0);
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20},
                java.nio.file.StandardOpenOption.APPEND);

        try (Journal j = Journal.open(dir, 1 << 20)) {
            assertEquals(20, j.lastSeq());
        }
        assertEquals(20, replay(dir, 0).size());
    }

    @Test
    @DisplayName("a flipped bit in the middle of a segment is detected, not silently replayed")
    void corruptionInTheMiddle(@TempDir Path dir) throws IOException {
        write(dir, 1, 300, 600);
        Path first = segments(dir).get(0);
        byte[] bytes = Files.readAllBytes(first);
        bytes[20] ^= 0x40;
        Files.write(first, bytes);
        assertThrows(IOException.class, () -> replay(dir, 0));
    }
}
