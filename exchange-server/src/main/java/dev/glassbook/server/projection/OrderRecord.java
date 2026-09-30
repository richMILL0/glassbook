package dev.glassbook.server.projection;

import dev.glassbook.core.model.CancelReason;
import dev.glassbook.core.model.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

@Entity
@Table(name = "trading_order")
public class OrderRecord implements Persistable<Long> {

    @Id
    private Long id;

    @Column(name = "participant_id", nullable = false)
    private long participantId;

    @Column(name = "instrument_id", nullable = false)
    private int instrumentId;

    @Column(nullable = false, length = 16)
    private String symbol;

    @Column(name = "client_order_id", length = 64)
    private String clientOrderId;

    @Column(nullable = false, length = 8)
    private String side;

    @Column(name = "order_type", nullable = false, length = 8)
    private String orderType;

    @Column(name = "time_in_force", nullable = false, length = 16)
    private String timeInForce;

    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private long quantity;

    @Column(name = "filled_quantity", nullable = false)
    private long filledQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OrderStatus status;

    @Column(name = "cancel_reason", length = 32)
    private String cancelReason;

    @Column(name = "created_seq", nullable = false)
    private long createdSeq;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean fresh = true;

    protected OrderRecord() { }

    public static OrderRecord accepted(Event.OrderAccepted a, String symbol) {
        OrderRecord r = new OrderRecord();
        r.id = a.orderId();
        r.participantId = a.participantId();
        r.instrumentId = a.instrumentId();
        r.symbol = symbol;
        r.clientOrderId = a.clientOrderId().isEmpty() ? null : a.clientOrderId();
        r.side = a.side().name();
        r.orderType = a.type().name();
        r.timeInForce = a.timeInForce().name();
        r.price = a.price();
        r.quantity = a.quantity();
        r.filledQuantity = 0;
        r.status = OrderStatus.OPEN;
        r.createdSeq = a.seq();
        r.createdAt = Instant.ofEpochMilli(a.timestamp());
        r.updatedAt = r.createdAt;
        return r;
    }

    public void fill(long quantityFilled, Instant at) {
        filledQuantity += quantityFilled;
        status = filledQuantity >= quantity ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        updatedAt = at;
    }

    public void cancel(CancelReason reason, Instant at) {
        status = OrderStatus.CANCELLED;
        cancelReason = reason.name();
        updatedAt = at;
    }

    @Override public Long getId() {
        return id;
    }
    @Override public boolean isNew() {
        return fresh;
    }

    @PostLoad                   /// После загрузки
    @PostPersist                /// После сохранения
    void markNotNew() {
        this.fresh = false;
    }

    public long getParticipantId() {
        return participantId;
    }

    public int getInstrumentId() {
        return instrumentId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getClientOrderId() {
        return clientOrderId;
    }

    public String getSide() {
        return side;
    }

    public String getOrderType() {
        return orderType;
    }

    public String getTimeInForce() {
        return timeInForce;
    }

    public long getPrice() {
        return price;
    }

    public long getQuantity() {
        return quantity;
    }

    public long getFilledQuantity() {
        return filledQuantity;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public long getCreatedSeq() {
        return createdSeq;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
