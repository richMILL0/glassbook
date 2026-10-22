package dev.glassbook.bench;

import dev.glassbook.core.engine.EngineConfig;
import dev.glassbook.core.engine.Exchange;
import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.EventSink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

public final class LatencyHarness {

    public static void main(String[] args) throws Exception {
        int commands = args.length > 0 ? Integer.parseInt(args[0]) : 2_000_000;
        int inflight = args.length > 1 ? Integer.parseInt(args[1]) : 256;
        boolean fsync = args.length > 2 && Boolean.parseBoolean(args[2]);
        System.out.printf("java %s, %d cpus, commands=%d, inflight=%d, fsync=%s%n",
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(), commands, inflight, fsync);

        rawEngine(Math.min(commands, 3_000_000));
        fullCore(commands, inflight, fsync);
    }

    private static void rawEngine(int commands) {
        for (int round = 0 ; round < 2; ++round) {          // round 0 warms the JIT, round 1 is reported
            Exchange x = new Exchange(Workload.INSTRUMENTS, 100_000);
            Workload w = new Workload(42);
            long seq = 0;
            long ts = 1_000;
            for (Command c : Workload.funding()) {
                x.apply(++seq, ++ts, c, EventSink.NOOP);
            }

            EventSink sink = w::observe;
            long start = System.nanoTime();
            for (int i = 0 ; i < commands ; ++i) {
                x.apply(++seq, ++ts, w.next(), sink);
            }

            double seconds = (System.nanoTime() - start) / 1e9;
            if (round == 1) {
                System.out.printf("Состояние : %,.0f команд/с  (%,d принято заявок, %,d сделок, %,d ждущие заявки)%n",
                        commands / seconds, w.accepted, w.trades, x.restingOrderCount());
            }
        }
    }

    private static void fullCore(int commands, int inflight, boolean fsync) throws Exception {
        Path dir = Files.createTempDirectory("glassbook-bench");
        Workload w = new Workload(7);
        EngineConfig config = EngineConfig.defaults(dir).withFsync(fsync).withSnapshotEvery(0);
        try (ExchangeCore core = new ExchangeCore(Workload.INSTRUMENTS, config, EventSink.NOOP)) {
            core.start();
            for (Command c : Workload.funding()) core.submit(c).get();

            Semaphore window = new Semaphore(inflight);
            long[] latencies = new long[commands];
            AtomicLong completed = new AtomicLong();
            long start = System.nanoTime();
            for (int i = 0; i < commands; i++) {
                window.acquire();
                Command command;
                synchronized (w) {
                    command = w.next();
                }
                long begin = System.nanoTime();
                int slot = i;
                CompletableFuture<?> f;
                while (true) {
                    try {
                        f = core.submit(command);
                        break;
                    } catch (RejectedExecutionException full) {
                        Thread.onSpinWait();
                    }
                }
                f.whenComplete((result, error) -> {
                    latencies[slot] = System.nanoTime() - begin;
                    if (result instanceof dev.glassbook.core.engine.CommandResult r) {
                        for (Event e : r.events()) synchronizedObserve(w, e);
                    }
                    completed.incrementAndGet();
                    window.release();
                });
            }
            window.acquire(inflight);
            double seconds = (System.nanoTime() - start) / 1e9;
            Arrays.sort(latencies);
            ExchangeCore.Stats s = core.stats();
            System.out.printf("core, fsync=%-5s  : %,.0f commands/s, batches=%,d (avg %.1f cmds/batch)%n",
                    fsync, commands / seconds, s.batches(), (double) s.commands() / Math.max(1, s.batches()));
            System.out.printf("  latency submit->ack: p50 %s  p90 %s  p99 %s  p99.9 %s  max %s%n",
                    us(latencies, 0.50), us(latencies, 0.90), us(latencies, 0.99), us(latencies, 0.999),
                    us(latencies, 1.0));
            System.out.printf("  engine thread: apply %.2f us/cmd, fsync %.1f us/batch%n",
                    s.applyNanos() / 1e3 / Math.max(1, s.commands()), s.fsyncNanos() / 1e3 / Math.max(1, s.batches()));
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void synchronizedObserve(Workload w, Event e) {
        synchronized (w) {
            w.observe(e);
        }
    }

    private static String us(long[] sorted, double q) {
        int i = (int) Math.min(sorted.length - 1, Math.ceil(q * sorted.length) - 1);
        return String.format("%,.1f us", sorted[Math.max(0, i)] / 1e3);
    }
}
