package dev.glassbook.core.engine;

import dev.glassbook.core.book.Order;
import dev.glassbook.core.book.OrderBook;
import dev.glassbook.core.ledger.Ledger;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.EventSink;
import dev.glassbook.core.model.Side;

import java.util.Arrays;
import java.util.Map;


final class Matcher {

    private final Ledger ledger;                /// Реестр
    private final Map<Long, Order> resting;     /// Стакан
    long nextTradeId = 1;                       /// Номер след сделки
    long filled;                                /// Сколько было исполнено за вызов
    long holdConsumed;                          /// Сколько из held получилось реализовать
    boolean selfTradePrevented;                 /// Если произошла сделка с самим собой

    private long[] touched = new long[16];      /// Цены уровней стакана, которые были затронуты
    private int touchedCount;                   /// Количество затронутых уровней

    Matcher(Ledger ledger, Map<Long, Order> resting) {
        this.ledger = ledger;
        this.resting = resting;
    }

    void match(long seq, long ts, Order taker, OrderBook book, long limit, boolean market, EventSink sink) {
        filled = 0;
        holdConsumed = 0;
        selfTradePrevented = false;
        touchedCount = 0;

        Side takerSide = taker.side;
        Side makerSide = takerSide.opposite();

        while (taker.remaining > 0) {
            Order maker = book.bestOrder(makerSide);
            if (maker == null || !OrderBook.priceAcceptable(takerSide, limit, maker.price)) {
                break;
            }

            if (maker.participantId == taker.participantId) {
                selfTradePrevented = true;
                break;
            }

            long qty = Math.min(taker.remaining, maker.remaining);
            long price = maker.price;
            long tradeId = nextTradeId++;

            Order buy;
            Order sell;

            long buyerReservedPrice;
            if (takerSide == Side.BUY) {
                buy = taker;
                sell = maker;
                buyerReservedPrice = market ? price : taker.price;
                holdConsumed += buyerReservedPrice * qty;
            } else {
                buy = maker;
                sell = taker;
                buyerReservedPrice = maker.price;
                holdConsumed += qty;
            }

            sink.accept(new Event.TradeExecuted(seq, ts, tradeId, taker.instrumentId, price, qty,
                    buy.id, sell.id, buy.participantId, sell.participantId, takerSide));
            ledger.settleTrade(seq, ts, tradeId, taker.instrumentId, buy.participantId, sell.participantId,
                    price, qty, buyerReservedPrice, sink);

            taker.remaining -= qty;
            filled += qty;
            touch(price);
            book.fill(maker, qty);
            if (maker.remaining == 0) {
                resting.remove(maker.id);
            }
        }

        for (int i = 0 ; i < touchedCount ; ++i) {
            long price = touched[i];
            sink.accept(new Event.BookLevelChanged(seq, ts, taker.instrumentId, makerSide, price,
                    book.levelQuantity(makerSide, price)));
        }
    }

    private void touch(long price) {
        if (touchedCount > 0 && touched[touchedCount - 1] == price) {
            return;
        }

        if (touchedCount == touched.length) {
            touched = Arrays.copyOf(touched, touchedCount * 2);
        }

        touched[touchedCount++] = price;
    }
}
