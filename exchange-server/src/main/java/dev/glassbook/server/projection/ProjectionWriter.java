package dev.glassbook.server.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbook.core.model.Event;
import dev.glassbook.server.config.GlassbookProperties;
import dev.glassbook.server.config.InstrumentCatalog;
import dev.glassbook.server.pipeline.EventGroup;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ProjectionWriter {

    public record TradeMessage(long tradeId, String symbol, long price, long quantity, long buyerId, long sellerId,
                               String takerSide, long seq, long timestamp) { }

    private static final Logger log = LoggerFactory.getLogger(ProjectionWriter.class);
    private static final int CURSOR_ID = 1;

    private final OrderRepository orders;
    private final TradeRepository trades;
    private final LedgerEntryRepository ledger;
    private final OutboxRepository outbox;
    private final CursorRepository cursors;
    private final InstrumentCatalog catalog;
    private final GlassbookProperties properties;
    private final ObjectMapper mapper;
    private final Counter inconsistencies;

    public ProjectionWriter(OrderRepository orders, TradeRepository trades, LedgerEntryRepository ledger,
                            OutboxRepository outbox, CursorRepository cursors, InstrumentCatalog catalog,
                            GlassbookProperties properties, ObjectMapper mapper, MeterRegistry meters) {
        this.orders = orders;
        this.trades = trades;
        this.ledger = ledger;
        this.outbox = outbox;
        this.cursors = cursors;
        this.catalog = catalog;
        this.properties = properties;
        this.mapper = mapper;
        this.inconsistencies = Counter.builder("glassbook.projection.inconsistencies")
                .description("events that referenced an order unknown to the database").register(meters);
    }

    @Transactional
    public long write(List<EventGroup> groups) {
        ProjectionCursor cursor = cursors.findById(CURSOR_ID).orElseGet(() -> new ProjectionCursor(CURSOR_ID, 0));
        long applied = cursor.getLastSeq();
        long highest = applied;

        List<Event> fresh = new ArrayList<>();
        for (EventGroup group : groups) {
            highest = Math.max(highest, group.seq());
            if (group.seq() > applied) fresh.addAll(group.events());
        }
        if (fresh.isEmpty()) return applied;

        // load the orders these events refer to, in one query
        Set<Long> createdHere = new HashSet<>();
        Set<Long> toLoad = new HashSet<>();
        for (Event e : fresh) {
            switch (e) {
                case Event.OrderAccepted a -> createdHere.add(a.orderId());
                case Event.TradeExecuted t -> {
                    toLoad.add(t.buyOrderId());
                    toLoad.add(t.sellOrderId());
                }
                case Event.OrderCancelled c -> toLoad.add(c.orderId());
                default -> { }
            }
        }
        toLoad.removeAll(createdHere);
        Map<Long, OrderRecord> touched = new HashMap<>();
        for (OrderRecord r : orders.findAllById(toLoad)) touched.put(r.getId(), r);

        List<TradeRecord> newTrades = new ArrayList<>();
        List<LedgerEntryRecord> newEntries = new ArrayList<>();
        List<OutboxRecord> newOutbox = new ArrayList<>();

        for (Event e : fresh) {
            switch (e) {
                case Event.OrderAccepted a -> {
                    OrderRecord r = OrderRecord.accepted(a, symbol(a.instrumentId()));
                    touched.put(r.getId(), r);
                }
                case Event.TradeExecuted t -> {
                    Instant at = Instant.ofEpochMilli(t.timestamp());
                    fill(touched, t.buyOrderId(), t.quantity(), at);
                    fill(touched, t.sellOrderId(), t.quantity(), at);
                    String symbol = symbol(t.instrumentId());
                    newTrades.add(TradeRecord.of(t, symbol));
                    if (properties.kafka().enabled()) {
                        newOutbox.add(new OutboxRecord(properties.kafka().tradesTopic(), symbol, toJson(
                                new TradeMessage(t.tradeId(), symbol, t.price(), t.quantity(), t.buyerId(),
                                        t.sellerId(), t.takerSide().name(), t.seq(), t.timestamp())), at));
                    }
                }
                case Event.OrderCancelled c -> {
                    OrderRecord r = touched.get(c.orderId());
                    if (r == null) inconsistencies.increment();
                    else r.cancel(c.reason(), Instant.ofEpochMilli(c.timestamp()));
                }
                case Event.Posted p -> newEntries.add(new LedgerEntryRecord(p));
                default -> { }
            }
        }

        orders.saveAll(touched.values());
        trades.saveAll(newTrades);
        ledger.saveAll(newEntries);
        outbox.saveAll(newOutbox);
        cursor.setLastSeq(highest);
        cursors.save(cursor);
        return highest;
    }

    public long cursor() {
        return cursors.findById(CURSOR_ID).map(ProjectionCursor::getLastSeq).orElse(0L);
    }

    private void fill(Map<Long, OrderRecord> touched, long orderId, long quantity, Instant at) {
        OrderRecord r = touched.get(orderId);
        if (r == null) {
            inconsistencies.increment();
            log.warn("trade refers to order {} which is not in the database", orderId);
        } else {
            r.fill(quantity, at);
        }
    }

    private String symbol(int instrumentId) {
        return catalog.byId(instrumentId).instrument().symbol();
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
