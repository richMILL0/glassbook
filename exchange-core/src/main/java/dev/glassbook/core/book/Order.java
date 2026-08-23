package dev.glassbook.core.book;

import dev.glassbook.core.model.Side;


public final class Order {
    public final long id;                   /// Уникальный ид
    public final long participantId;        /// Кто выставил заявку
    public final int instrumentId;          /// К какой "валюте" привязан
    public final Side side;                 /// Продажа или покупка

    public final long price;                /// Цена за единицу инструмента
    public final long quantity;             /// Сколько единиц заявка хотела купить|продать изначально
    public long remaining;                  /// Сколько ещё не исполнено

    public final long seq;                  /// Порядковый номер заявки в движке
    public final long timestamp;            /// Время приёма заявки движком

    Order prev;
    Order next;
    PriceLevel level;

    public Order(long id, long participantId, int instrumentId, Side side, long price, long quantity,
                 long remaining, long seq, long timestamp) {
        this.id = id;
        this.participantId = participantId;
        this.instrumentId = instrumentId;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.remaining = remaining;
        this.seq = seq;
        this.timestamp = timestamp;
    }

    public boolean isResting() {
        return level != null;
    }

    @Override
    public String toString() {
        return "Order[" + id + " " + side + " " + remaining + "/" + quantity + " @" + price + " p" + participantId + "]";
    }
}
