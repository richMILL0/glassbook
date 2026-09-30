package dev.glassbook.server.projection;

import dev.glassbook.core.model.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "trade")
public class TradeRecord implements Persistable<Long> {

    @Id
    private Long id;

    @Column(nullable = false)
    private long seq;

    @Column(name = "instrument_id", nullable = false)
    private int instrumentId;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "buy_order_id", nullable = false)
    private long buyOrderId;

    @Column(name = "sell_order_id", nullable = false)
    private long sellOrderId;

    @Column(name = "buyer_id", nullable = false)
    private long buyerId;

    @Column(name = "seller_id", nullable = false)
    private long sellerId;

    @Column(name = "taker_side", nullable = false, length = 8)
    private String takerSide;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Transient                  /// Хранится только в памяти, в таблице его нет
    private boolean fresh = true;

    protected TradeRecord() { }

    public static TradeRecord of(Event.TradeExecuted t, String symbol) {
        TradeRecord r = new TradeRecord();
        r.id = t.tradeId();
        r.seq = t.seq();
        r.instrumentId = t.instrumentId();
        r.symbol = symbol;
        r.price = t.price();
        r.quantity = t.quantity();
        r.buyOrderId = t.buyOrderId();
        r.sellOrderId = t.sellOrderId();
        r.buyerId = t.buyerId();
        r.sellerId = t.sellerId();
        r.takerSide = t.takerSide().name();
        r.executedAt = Instant.ofEpochMilli(t.timestamp());
        return r;
    }

    @Override
    public Long getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.fresh = false;
    }

    public long getSeq() {
        return seq;
    }

    public int getInstrumentId() {
        return instrumentId;

    }

    public String getSymbol() {
        return symbol;
    }

    public long getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getBuyOrderId() {
        return buyOrderId;
    }

    public long getSellOrderId() {
        return sellOrderId;
    }

    public long getBuyerId() {
        return buyerId;
    }

    public long getSellerId() {
        return sellerId;
    }

    public String getTakerSide() {
        return takerSide;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}
