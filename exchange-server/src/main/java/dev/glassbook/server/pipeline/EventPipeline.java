package dev.glassbook.server.pipeline;

import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.EventSink;
import dev.glassbook.server.market.MarketDataService;
import dev.glassbook.server.projection.ProjectionWriter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
public class EventPipeline implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(EventPipeline.class);

    private final MarketDataService marketData;
    private final ProjectionWriter projection;
    private final BlockingQueue<EventGroup> queue = new LinkedBlockingQueue<>(200_000);

    private List<Event> current = new ArrayList<>(16);

    private volatile boolean running;
    private volatile long projectedSeq;
    private Thread thread;

    public EventPipeline(MarketDataService marketData, ProjectionWriter projection) {
        this.marketData = marketData;
        this.projection = projection;
    }

    @PostConstruct
    void start() {
        running = true;
        projectedSeq = projection.cursor();
        thread = new Thread(this::loop, "event-pipeline");
        thread.start();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        if (thread != null) {
            thread.join(30_000);
        }
    }

    public long projectedSeq() {
        return projectedSeq;
    }

    public int backlog() {
        return queue.size();
    }

    // ------------------------------------------------------------------ EventSink (Поток движка)

    @Override
    public void accept(Event event) {
        current.add(event);
    }

    @Override
    public void commandApplied(long seq) {
        EventGroup group = new EventGroup(seq, current);
        current = new ArrayList<>(16);
        try {
            queue.put(group);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ Поток consumer

    private void loop() {
        List<EventGroup> batch = new ArrayList<>(256);
        while (true) {
            try {
                EventGroup first = queue.poll(50, TimeUnit.MILLISECONDS);
                if (first == null) {
                    if (!running) {
                        return;
                    }
                    continue;
                }

                batch.clear();
                batch.add(first);
                queue.drainTo(batch, 255);
                process(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void process(List<EventGroup> batch) throws InterruptedException {
        for (EventGroup group : batch) {
            try {
                marketData.apply(group);
            } catch (RuntimeException e) {
                log.error("market data update failed for seq {}", group.seq(), e);
            }
        }

        long backoff = 200;
        int attempts = 0;
        while (true) {
            try {
                projectedSeq = projection.write(batch);
                return;
            } catch (RuntimeException e) {
                attempts++;
                if (!running && attempts > 5) {
                    log.error("giving up projecting while shutting down; the journal will re-publish these events", e);
                    return;
                }

                log.error("projection failed (attempt {}), retrying in {} ms", attempts, backoff, e);
                Thread.sleep(backoff);
                backoff = Math.min(backoff * 2, 10_000);
            }
        }
    }
}
