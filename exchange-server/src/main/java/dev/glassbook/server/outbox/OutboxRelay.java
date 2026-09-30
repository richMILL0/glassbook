package dev.glassbook.server.outbox;

import dev.glassbook.server.projection.OutboxRecord;
import dev.glassbook.server.projection.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


@Component
@ConditionalOnProperty(prefix = "glassbook.kafka", name = "enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    private final Counter published;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transaction, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transaction = transaction;
        this.published = Counter.builder("glassbook.outbox.published").register(meters);
    }

    @Scheduled(fixedDelay = 200)
    public void relay() {
        try {
            Integer sent = transaction.execute(status -> {
                List<OutboxRecord> batch = outbox.lockNextBatch(200);
                if (batch.isEmpty()) return 0;
                List<CompletableFuture<?>> acks = new ArrayList<>(batch.size());

                for (OutboxRecord r : batch) acks.add(kafka.send(r.getTopic(), r.getEventKey(), r.getPayload()));
                try {
                    for (CompletableFuture<?> ack : acks) ack.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("Kafka не подтвердил получение пакета", e);
                }

                Instant now = Instant.now();
                for (OutboxRecord r : batch) r.markPublished(now);
                return batch.size();
            });
            if (sent != null && sent > 0) published.increment(sent);
        } catch (RuntimeException e) {
            log.warn("outbox relay failed, will retry: {}", e.getMessage());
        }
    }
}
