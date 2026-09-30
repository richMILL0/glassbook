package dev.glassbook.server.metrics;

import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.server.pipeline.EventPipeline;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

@Component
public class EngineMetrics implements MeterBinder {

    private final ExchangeCore core;
    private final EventPipeline pipeline;

    public EngineMetrics(ExchangeCore core, EventPipeline pipeline) {
        this.core = core;
        this.pipeline = pipeline;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("glassbook.engine.queue.depth", core, c -> c.stats().queueDepth())
                .description("commands waiting for the engine thread").register(registry);
        Gauge.builder("glassbook.engine.seq", core, c -> c.stats().lastSeq())
                .description("sequence number of the last applied command").register(registry);
        Gauge.builder("glassbook.projection.lag", core, c -> c.stats().lastSeq() - pipeline.projectedSeq())
                .description("commands applied by the engine but not yet stored in the database").register(registry);
        Gauge.builder("glassbook.pipeline.backlog", pipeline, EventPipeline::backlog)
                .description("event groups waiting for the projector").register(registry);
        FunctionCounter.builder("glassbook.engine.commands", core, c -> c.stats().commands())
                .description("commands processed").register(registry);
        FunctionCounter.builder("glassbook.engine.batches", core, c -> c.stats().batches())
                .description("group-commit batches (one fsync each)").register(registry);
        FunctionCounter.builder("glassbook.engine.journal.seconds", core, c -> c.stats().fsyncNanos() / 1e9)
                .description("total time spent writing and syncing the journal").register(registry);
        FunctionCounter.builder("glassbook.engine.apply.seconds", core, c -> c.stats().applyNanos() / 1e9)
                .description("total time spent in the state machine").register(registry);
        FunctionCounter.builder("glassbook.engine.snapshots", core, c -> c.stats().snapshots())
                .description("snapshots written").register(registry);
    }
}
