package dev.glassbook.core.engine;

import java.nio.file.Path;
import java.util.function.LongSupplier;


public record EngineConfig(Path dataDir, int queueCapacity, int maxBatch, boolean fsync, long snapshotEveryCommands,
                           long segmentMaxBytes, int idempotencyCapacity, int snapshotsToKeep,
                           boolean emitEventsOnReplay, LongSupplier clock) {

    public static EngineConfig defaults(Path dataDir) {
        return new EngineConfig(dataDir, 65_536, 512, true, 200_000, 32L * 1024 * 1024, 1_000_000, 2, true,
                System::currentTimeMillis);
    }

    public EngineConfig withFsync(boolean value) {
        return new EngineConfig(dataDir, queueCapacity, maxBatch, value, snapshotEveryCommands, segmentMaxBytes,
                idempotencyCapacity, snapshotsToKeep, emitEventsOnReplay, clock);
    }

    public EngineConfig withSnapshotEvery(long commands) {
        return new EngineConfig(dataDir, queueCapacity, maxBatch, fsync, commands, segmentMaxBytes,
                idempotencyCapacity, snapshotsToKeep, emitEventsOnReplay, clock);
    }

    public EngineConfig withSegmentMaxBytes(long bytes) {
        return new EngineConfig(dataDir, queueCapacity, maxBatch, fsync, snapshotEveryCommands, bytes,
                idempotencyCapacity, snapshotsToKeep, emitEventsOnReplay, clock);
    }

    public EngineConfig withClock(LongSupplier value) {
        return new EngineConfig(dataDir, queueCapacity, maxBatch, fsync, snapshotEveryCommands, segmentMaxBytes,
                idempotencyCapacity, snapshotsToKeep, emitEventsOnReplay, value);
    }

    public EngineConfig withEmitEventsOnReplay(boolean value) {
        return new EngineConfig(dataDir, queueCapacity, maxBatch, fsync, snapshotEveryCommands, segmentMaxBytes,
                idempotencyCapacity, snapshotsToKeep, value, clock);
    }
}
