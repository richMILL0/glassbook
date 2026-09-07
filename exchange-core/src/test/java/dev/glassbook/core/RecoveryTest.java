package dev.glassbook.core;

import dev.glassbook.core.engine.EngineConfig;
import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.core.engine.ExchangeSnapshot;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    private static EngineConfig config(Path dir) {
        return EngineConfig.defaults(dir).withFsync(false).withSnapshotEvery(0);
    }

    private static void runAll(ExchangeCore core, List<Command> script) throws Exception {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (Command c : script) futures.add(core.submit(c));
        for (CompletableFuture<?> f : futures) f.get(30, TimeUnit.SECONDS);
    }

    private static String fingerprint(ExchangeCore core) throws Exception {
        return core.query(ExchangeSnapshot::fingerprint).get(30, TimeUnit.SECONDS);
    }

    private static void copyDirectory(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }

    @Test
    @DisplayName("clean restart: the recovered state is bit-identical (journal only)")
    void restartFromJournal(@TempDir Path dir) throws Exception {
        List<Command> script = new ScenarioGenerator(7, ScenarioGenerator.Mode.POOR).script(1_500);
        String before;
        try (ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir), e -> { })) {
            core.start();
            runAll(core, script);
            before = fingerprint(core);
        }
        try (ExchangeCore again = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir), e -> { })) {
            again.start();
            assertEquals(before, fingerprint(again));
            assertEquals(script.size(), again.stats().lastSeq());
            again.query(x -> { Invariants.check(x, null, null); return 0; }).get();
        }
    }

    @Test
    @DisplayName("crash recovery with snapshots: snapshot + journal tail rebuild the identical state, old files are pruned")
    void crashWithSnapshots(@TempDir Path dir, @TempDir Path crashCopy) throws Exception {
        List<Command> script = new ScenarioGenerator(11, ScenarioGenerator.Mode.POOR).script(2_000);
        EngineConfig cfg = EngineConfig.defaults(dir).withFsync(true).withSnapshotEvery(300).withSegmentMaxBytes(8_192);
        ExchangeCore running = new ExchangeCore(TestExchange.INSTRUMENTS, cfg, e -> { });
        running.start();
        try {
            runAll(running, script);
            String live = fingerprint(running);
            assertTrue(running.stats().snapshots() >= 3, "expected periodic snapshots");

            // "kill -9": copy the files while the process is still running and never close it
            copyDirectory(dir, crashCopy);
            try (ExchangeCore recovered = new ExchangeCore(TestExchange.INSTRUMENTS,
                    EngineConfig.defaults(crashCopy).withFsync(false).withSnapshotEvery(0), e -> { })) {
                recovered.start();
                assertEquals(live, fingerprint(recovered));
                assertEquals(script.size(), recovered.stats().lastSeq());
            }
            try (Stream<Path> s = Files.list(dir.resolve("snapshots"))) {
                assertEquals(2, s.count(), "only the newest snapshots are retained");
            }
        } finally {
            running.close();
        }
    }

    @Test
    @DisplayName("a torn last journal record after a crash loses only that command")
    void tornTail(@TempDir Path dir, @TempDir Path crashCopy) throws Exception {
        List<Command> script = new ScenarioGenerator(5, ScenarioGenerator.Mode.POOR).script(400);
        ExchangeCore running = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir), e -> { });
        running.start();
        try {
            runAll(running, script);
            copyDirectory(dir, crashCopy);
        } finally {
            running.close();
        }
        Path wal;
        try (Stream<Path> s = Files.list(crashCopy.resolve("journal"))) {
            wal = s.sorted().reduce((a, b) -> b).orElseThrow();
        }
        try (RandomAccessFile raf = new RandomAccessFile(wal.toFile(), "rw")) {
            raf.setLength(raf.length() - 5);
        }

        try (ExchangeCore recovered = new ExchangeCore(TestExchange.INSTRUMENTS, config(crashCopy), e -> { })) {
            recovered.start();
            assertEquals(script.size() - 1, recovered.stats().lastSeq());
            recovered.query(x -> { Invariants.check(x, null, null); return 0; }).get();
        }
    }

    @Test
    @DisplayName("after recovery the engine continues the sequence and can be recovered again")
    void continueAfterRecovery(@TempDir Path dir) throws Exception {
        List<Command> script = new ScenarioGenerator(3, ScenarioGenerator.Mode.POOR).script(900);
        TestExchange reference = new TestExchange();      // timestamps 1001, 1002, ... one per command
        script.forEach(reference::apply);
        // one clock shared by all three runs, so the live engine stamps commands exactly like the reference
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000);

        try (ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir).withClock(clock::incrementAndGet), e -> { })) {
            core.start();
            runAll(core, script.subList(0, 300));
        }
        try (ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS,
                config(dir).withSnapshotEvery(100).withClock(clock::incrementAndGet), e -> { })) {
            core.start();
            runAll(core, script.subList(300, 600));
        }
        try (ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir).withClock(clock::incrementAndGet), e -> { })) {
            core.start();
            runAll(core, script.subList(600, script.size()));
            assertEquals(ExchangeSnapshot.fingerprint(reference.exchange), fingerprint(core));
        }
    }

    @Test
    @DisplayName("replay re-publishes events so a projection that missed them can catch up")
    void replayEmitsEvents(@TempDir Path dir) throws Exception {
        List<Command> script = new ScenarioGenerator(9, ScenarioGenerator.Mode.POOR).script(300);
        List<Event> first = new CopyOnWriteArrayList<>();
        try (ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir), first::add)) {
            core.start();
            runAll(core, script);
        }
        List<Event> replayed = new CopyOnWriteArrayList<>();
        try (ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir), replayed::add)) {
            core.start();
        }
        assertEquals(first, replayed);
    }

    @Test
    @DisplayName("every command is followed by exactly one boundary marker, live and during replay")
    void commandBoundaries(@TempDir Path dir) throws Exception {
        List<Command> script = new ScenarioGenerator(21, ScenarioGenerator.Mode.POOR).script(200);
        class Recorder implements dev.glassbook.core.model.EventSink {
            final List<Long> boundaries = new CopyOnWriteArrayList<>();
            long lastEventSeq = 0;
            @Override public void accept(Event event) {
                if (event.seq() < lastEventSeq) throw new AssertionError("events out of order");
                lastEventSeq = event.seq();
            }
            @Override public void commandApplied(long seq) {
                if (seq != lastEventSeq) throw new AssertionError("marker " + seq + " after events of " + lastEventSeq);
                boundaries.add(seq);
            }
        }
        Recorder live = new Recorder();
        try (ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir), live)) {
            core.start();
            runAll(core, script);
        }
        assertEquals(script.size() + 0L, live.boundaries.size() + 0L);
        Recorder replay = new Recorder();
        try (ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir), replay)) {
            core.start();
        }
        assertEquals(live.boundaries, replay.boundaries);
    }

    @Test
    @DisplayName("many threads submitting at once: every command gets a unique sequence, invariants hold")
    void concurrentSubmitters(@TempDir Path dir) throws Exception {
        int threads = 8;
        int perThread = 1_000;
        ConcurrentHashMap<Long, Boolean> seen = new ConcurrentHashMap<>();
        try (ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS, config(dir), e -> { })) {
            core.start();
            for (long p = 1; p <= threads; p++) {
                core.submit(new Command.Deposit(p, 0, 1_000_000_000L)).get();
                core.submit(new Command.Deposit(p, 1, 1_000_000L)).get();
            }
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Void>> done = new ArrayList<>();
            for (int t = 1; t <= threads; t++) {
                long participant = t;
                done.add(CompletableFuture.runAsync(() -> {
                    try {
                        go.await();
                        java.util.SplittableRandom rnd = new java.util.SplittableRandom(participant);
                        for (int i = 0; i < perThread; i++) {
                            Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
                            var result = core.submit(new Command.PlaceOrder(participant, 1, "", side, OrderType.LIMIT,
                                    TimeInForce.GTC, 990 + rnd.nextLong(21), 1 + rnd.nextLong(20))).get();
                            if (seen.put(result.seq(), true) != null) throw new AssertionError("duplicate seq");
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, pool));
            }
            go.countDown();
            for (CompletableFuture<Void> f : done) f.get(60, TimeUnit.SECONDS);
            pool.shutdown();

            assertEquals(threads * perThread, seen.size());
            core.query(x -> { Invariants.check(x, null, null); return 0; }).get();
            assertEquals(threads * perThread + threads * 2, core.stats().lastSeq());
        }
    }

    @Test
    @DisplayName("a full queue fails fast instead of blocking; a stopped engine refuses work")
    void backPressureAndShutdown(@TempDir Path dir) throws Exception {
        EngineConfig tiny = new EngineConfig(dir, 4, 1, false, 0, 1 << 20, 1000, 2, true, System::currentTimeMillis);
        ExchangeCore core = new ExchangeCore(TestExchange.INSTRUMENTS, tiny, e -> { });
        core.start();
        CountDownLatch block = new CountDownLatch(1);
        core.query(x -> { try { block.await(); } catch (InterruptedException ignored) { } return 0; });
        Thread.sleep(100);                       // the engine thread is now stuck inside the query
        int rejected = 0;
        List<CompletableFuture<?>> accepted = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            CompletableFuture<?> f = core.submit(new Command.Deposit(1, 0, 10));
            if (f.isCompletedExceptionally()) rejected++;
            else accepted.add(f);
        }
        assertTrue(rejected >= 5, "expected back-pressure, rejected=" + rejected);
        block.countDown();
        for (CompletableFuture<?> f : accepted) f.get(10, TimeUnit.SECONDS);
        core.close();

        var after = core.submit(new Command.Deposit(1, 0, 10));
        var ex = assertThrows(java.util.concurrent.ExecutionException.class, () -> after.get(1, TimeUnit.SECONDS));
        assertTrue(ex.getCause() instanceof RejectedExecutionException);
    }
}
