package dev.glassbook.core.engine;

import dev.glassbook.core.journal.Journal;
import dev.glassbook.core.journal.SnapshotStore;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.EventSink;
import dev.glassbook.core.model.Instrument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


public final class ExchangeCore implements AutoCloseable {

    public record Stats(long lastSeq, int queueDepth, long commands, long batches, long fsyncNanos, long applyNanos,
                        long snapshots, long lastSnapshotSeq, State state) {}

    public enum State { NEW, RUNNING, CLOSING, FAILED, CLOSED }

    private abstract static class Item {
        abstract void fail(Throwable t);
    }

    private static final class CommandItem extends Item {
        final Command command;
        final CompletableFuture<CommandResult> future = new CompletableFuture<>();
        long seq;
        long ts;

        CommandItem(Command command) {
            this.command = command;
        }

        @Override
        void fail(Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private static final class CallItem<T> extends Item {
        final Function<Exchange, T> fn;
        final CompletableFuture<T> future = new CompletableFuture<>();

        CallItem(Function<Exchange, T> fn) {
            this.fn = fn;
        }

        void run(Exchange x) {
            try {
                future.complete(fn.apply(x));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }

        @Override
        void fail(Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private static final System.Logger LOG = System.getLogger("dev.glassbook.engine");

    private final List<Instrument> instruments;
    private final EngineConfig config;
    private final EventSink downstream;
    private final BlockingQueue<Item> queue;

    private volatile State state = State.NEW;
    private Thread thread;

    // owned by the engine thread after start()
    private Exchange exchange;
    private Journal journal;
    private SnapshotStore snapshots;
    private long seqCounter;
    private long lastSnapshotSeq;

    private volatile long publishedSeq;
    private volatile long commandCount;
    private volatile long batchCount;
    private volatile long fsyncNanos;
    private volatile long applyNanos;
    private volatile long snapshotCount;

    public ExchangeCore(List<Instrument> instruments, EngineConfig config, EventSink downstream) {
        this.instruments = List.copyOf(instruments);
        this.config = config;
        this.downstream = downstream;
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
    }


    public synchronized void start() throws IOException {
        if (state != State.NEW) {
            throw new IllegalStateException("Уже запущен");
        }

        Path journalDir = config.dataDir().resolve("journal");
        snapshots = new SnapshotStore(config.dataDir().resolve("snapshots"));

        long started = System.nanoTime();
        Exchange recovered = snapshots.loadLatest(instruments, config.idempotencyCapacity());
        Exchange x = recovered != null ? recovered : new Exchange(instruments, config.idempotencyCapacity());
        long snapshotSeq = x.lastSeq();

        journal = Journal.open(journalDir, config.segmentMaxBytes());
        EventSink replaySink = config.emitEventsOnReplay() ? safe(downstream) : EventSink.NOOP;
        long[] replayed = {0};
        Journal.replay(journalDir, snapshotSeq, (seq, ts, command) -> {
            x.apply(seq, ts, command, replaySink);
            replaySink.commandApplied(seq);
            replayed[0]++;
        });

        exchange = x;
        seqCounter = x.lastSeq();
        publishedSeq = seqCounter;
        lastSnapshotSeq = snapshotSeq;
        LOG.log(System.Logger.Level.INFO, "recovered: snapshot seq={0}, replayed {1} commands, now at seq={2} in {3} ms",
                snapshotSeq, replayed[0], seqCounter, (System.nanoTime() - started) / 1_000_000);

        state = State.RUNNING;
        thread = new Thread(this::run, "exchange-engine");
        thread.start();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (state == State.NEW) {
                state = State.CLOSED;
                return;
            }
            if (state == State.RUNNING) state = State.CLOSING;
        }
        try {
            if (thread != null) thread.join(30_000);
            if (journal != null) journal.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "error closing journal", e);
        }
        if (state != State.FAILED) state = State.CLOSED;
    }

    public State state() {
        return state;
    }

    // ------------------------------------------------------------------ API

    /**
     * Enqueues a command. The returned future completes after the command is durable and applied. It fails with
     * {@link RejectedExecutionException} when the queue is full (back-pressure) or the engine is not running.
     */
    public CompletableFuture<CommandResult> submit(Command command) {
        Objects.requireNonNull(command, "command");
        if (command instanceof Command.PlaceOrder p
                && (p.side() == null || p.type() == null || p.timeInForce() == null)) {
            throw new IllegalArgumentException("side, type and timeInForce are required");
        }
        CommandItem item = new CommandItem(command);
        enqueue(item);
        return item.future;
    }

    /** Runs a read-only function on the engine thread, between commands. */
    public <T> CompletableFuture<T> query(Function<Exchange, T> function) {
        CallItem<T> item = new CallItem<>(function);
        enqueue(item);
        return item.future;
    }

    /** Writes a snapshot now, rolls the journal segment and prunes what the retained snapshots make redundant. */
    public CompletableFuture<Long> snapshot() {
        return query(x -> {
            try {
                return takeSnapshot();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public Stats stats() {
        return new Stats(publishedSeq, queue.size(), commandCount, batchCount, fsyncNanos, applyNanos,
                snapshotCount, lastSnapshotSeq, state);
    }

    private void enqueue(Item item) {
        if (state != State.RUNNING) {
            item.fail(new RejectedExecutionException("exchange is not running (" + state + ")"));
            return;
        }
        if (!queue.offer(item)) {
            item.fail(new RejectedExecutionException("exchange queue is full"));
        }
    }

    // ------------------------------------------------------------------ engine thread

    private void run() {
        List<Item> batch = new ArrayList<>(config.maxBatch());
        try {
            while (true) {
                Item first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) {
                    if (state != State.RUNNING) break;
                    continue;
                }
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, config.maxBatch() - 1);
                process(batch);
                if (state == State.FAILED) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            RejectedExecutionException stopped = new RejectedExecutionException("exchange stopped (" + state + ")");
            Item pending;
            while ((pending = queue.poll()) != null) pending.fail(stopped);
        }
    }

    private void process(List<Item> batch) {
        // 1. sequence + write-ahead
        long t0 = System.nanoTime();
        try {
            boolean wrote = false;
            for (Item item : batch) {
                if (item instanceof CommandItem c) {
                    c.seq = ++seqCounter;
                    c.ts = config.clock().getAsLong();
                    journal.append(c.seq, c.ts, c.command);
                    wrote = true;
                }
            }
            if (wrote) journal.commit(config.fsync());
        } catch (IOException | RuntimeException e) {
            fatal("journal write failed", e, batch);
            return;
        }
        long t1 = System.nanoTime();
        fsyncNanos += t1 - t0;

        // 2. apply, in journal order
        EventSink sink = safe(downstream);
        for (int i = 0; i < batch.size(); i++) {
            Item item = batch.get(i);
            if (item instanceof CommandItem c) {
                List<Event> events = new ArrayList<>(8);
                try {
                    exchange.apply(c.seq, c.ts, c.command, e -> {
                        events.add(e);
                        sink.accept(e);
                    });
                } catch (RuntimeException e) {
                    fatal("state machine failed on seq " + c.seq + " (" + c.command + ")", e,
                            batch.subList(i, batch.size()));
                    return;
                }
                sink.commandApplied(c.seq);
                publishedSeq = c.seq;
                c.future.complete(new CommandResult(c.seq, events));
            } else if (item instanceof CallItem<?> call) {
                call.run(exchange);
            }
        }
        applyNanos += System.nanoTime() - t1;
        publishedSeq = exchange.lastSeq();
        commandCount = seqCounter;
        batchCount++;

        // 3. periodic snapshot
        if (config.snapshotEveryCommands() > 0 && exchange.lastSeq() - lastSnapshotSeq >= config.snapshotEveryCommands()) {
            try {
                takeSnapshot();
            } catch (IOException e) {
                // not fatal: the journal still has everything, recovery just takes longer
                LOG.log(System.Logger.Level.WARNING, "snapshot failed", e);
            }
        }
    }

    private long takeSnapshot() throws IOException {
        long seq = exchange.lastSeq();
        if (seq == lastSnapshotSeq && snapshotCount > 0) return seq;
        snapshots.write(exchange);
        journal.rollSegment();
        long oldestKept = snapshots.prune(config.snapshotsToKeep());
        journal.purgeBefore(oldestKept + 1);
        lastSnapshotSeq = seq;
        snapshotCount++;
        return seq;
    }

    private void fatal(String what, Throwable cause, List<Item> unfinished) {
        LOG.log(System.Logger.Level.ERROR, "FATAL: " + what + ", engine stopping", cause);
        state = State.FAILED;
        for (Item item : unfinished) item.fail(cause);
    }

    private static EventSink safe(EventSink target) {
        return new EventSink() {
            @Override
            public void accept(Event event) {
                try {
                    target.accept(event);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "event sink threw on " + event, e);
                }
            }

            @Override
            public void commandApplied(long seq) {
                try {
                    target.commandApplied(seq);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "event sink threw at end of command " + seq, e);
                }
            }
        };
    }
}
