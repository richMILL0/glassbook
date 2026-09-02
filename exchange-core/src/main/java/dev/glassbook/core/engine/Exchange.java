package dev.glassbook.core.engine;

import dev.glassbook.core.book.Order;
import dev.glassbook.core.book.OrderBook;
import dev.glassbook.core.ledger.Account;
import dev.glassbook.core.ledger.Ledger;
import dev.glassbook.core.model.CancelReason;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.EventSink;
import dev.glassbook.core.model.Instrument;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.RejectReason;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class Exchange {

    public static final int MAX_CLIENT_ORDER_ID_LENGTH = 64;

    private final Instrument[] instruments;                             /// Index = instrumentId, [0] не используется
    private final Map<String, Instrument> bySymbol = new HashMap<>();   /// Для поиска инструмента по тикеру
    private final OrderBook[] books;                                    /// По одному OrderBook на инструмент
    private final boolean[] halted;                                     /// Для кого торги завершены
    private final Ledger ledger;                                        /// Все счета
    private final Map<Long, Order> resting = new HashMap<>();           /// Все ждущие заявки(Для отмены по номеру)
    private final IdempotencyIndex idempotency;                         /// Id тех, кого уже видел
    private final Matcher matcher;                                      /// Цикл возможных пересечений

    private long lastSeq;                                               /// Номер последней команды
    private long nextOrderId = 1;                                       /// Счетчик номеров заявок

    public Exchange(List<Instrument> config, int idempotencyCapacity) {
        if (config.isEmpty()) {
            throw new IllegalArgumentException("Нужен хотя бы 1 инструмент");
        }

        int n = config.size();
        this.instruments = new Instrument[n + 1];
        for (Instrument i : config) {
            if (i.id() > n || instruments[i.id()] != null) {
                throw new IllegalArgumentException("Инструменты должны идти плотно друг к другу без пропусков. " +
                        "Последний верный индекс" + i);
            }

            instruments[i.id()] = i;
            if (bySymbol.put(i.symbol(), i) != null) {
                throw new IllegalArgumentException("Инструмент с таким навзанием уже существует " + i.symbol());
            }
        }

        this.books = new OrderBook[n + 1];
        for (int id = 1 ; id <= n ; ++id) {
            books[id] = new OrderBook(instruments[id]);
        }

        this.halted = new boolean[n + 1];
        this.ledger = new Ledger(n + 1);
        this.idempotency = new IdempotencyIndex(idempotencyCapacity);
        this.matcher = new Matcher(ledger, resting);
    }

    // ------------------------------------------------------------------ Геттеры

    public long lastSeq() {
        return lastSeq;
    }

    public long nextOrderId() {
        return nextOrderId;
    }

    public long nextTradeId() {
        return matcher.nextTradeId;
    }

    public Ledger ledger() {
        return ledger;
    }

    public int instrumentCount() {
        return instruments.length - 1;
    }

    public Instrument instrument(int id) {
        return id >= 1 && id < instruments.length ? instruments[id] : null;
    }

    public Instrument instrument(String symbol) {
        return bySymbol.get(symbol);
    }

    public OrderBook book(int instrumentId) {
        return books[instrumentId];
    }

    public boolean isHalted(int instrumentId) {
        return halted[instrumentId];
    }

    public Order restingOrder(long orderId) {
        return resting.get(orderId);
    }

    public int restingOrderCount() {
        return resting.size();
    }

    // ------------------------------------------------------------------

    public void apply(long seq, long ts, Command command, EventSink sink) {
        if (seq != lastSeq + 1) {
            throw new IllegalStateException("Должен был получить Seq " + (lastSeq + 1) + " но получил " + seq);
        }

        lastSeq = seq;
        switch (command) {
            case Command.PlaceOrder c  -> placeOrder(seq, ts, c, sink);
            case Command.CancelOrder c -> cancelOrder(seq, ts, c, sink);
            case Command.Deposit c     -> deposit(seq, ts, c, sink);
            case Command.SetHalted c   -> setHalted(seq, ts, c, sink);
        }
    }

    // ------------------------------------------------------------------

    private void placeOrder(long seq, long ts, Command.PlaceOrder c, EventSink sink) {
        Instrument inst = instrument(c.instrumentId());
        if (inst == null) {
            rejectOrder(seq, ts, c, RejectReason.UNKNOWN_INSTRUMENT, 0, sink);
            return;
        }

        if (c.side() == null || c.type() == null || c.timeInForce() == null) {
            rejectOrder(seq, ts, c, RejectReason.INVALID_ORDER, 0, sink);
            return;
        }

        Account account = c.participantId() == Ledger.SYSTEM ? null : ledger.account(c.participantId());
        if (account == null) {
            rejectOrder(seq, ts, c, RejectReason.UNKNOWN_ACCOUNT, 0, sink);
            return;
        }

        if (halted[inst.id()]) {
            rejectOrder(seq, ts, c, RejectReason.INSTRUMENT_HALTED, 0, sink);
            return;
        }

        long qty = c.quantity();
        if (!inst.isQuantityInRange(qty)) {
            rejectOrder(seq, ts, c, RejectReason.INVALID_QUANTITY, 0, sink);
            return;
        }

        if (!inst.isOnLot(qty)) {
            rejectOrder(seq, ts, c, RejectReason.LOT_SIZE_VIOLATION, 0, sink);
            return;
        }

        boolean market = c.type() == OrderType.MARKET;
        TimeInForce tif = c.timeInForce();
        if (market) {
            if (c.price() != 0) {
                rejectOrder(seq, ts, c, RejectReason.INVALID_PRICE, 0, sink);
                return;
            }

            if (tif == TimeInForce.POST_ONLY) {
                rejectOrder(seq, ts, c, RejectReason.INVALID_ORDER, 0, sink);
                return;
            }

            if (tif == TimeInForce.GTC) {
                tif = TimeInForce.IOC;
            }
        } else {
            if (!inst.isPriceInRange(c.price())) {
                rejectOrder(seq, ts, c, RejectReason.INVALID_PRICE, 0, sink);
                return;
            }

            if (!inst.isOnTick(c.price())) {
                rejectOrder(seq, ts, c, RejectReason.TICK_SIZE_VIOLATION, 0, sink);
                return;
            }
        }

        String clientId = c.clientOrderId();
        if (clientId.length() > MAX_CLIENT_ORDER_ID_LENGTH) {
            rejectOrder(seq, ts, c, RejectReason.INVALID_ORDER, 0, sink);
            return;
        }

        String idemKey = null;
        if (!clientId.isEmpty()) {
            idemKey = IdempotencyIndex.key(c.participantId(), clientId);
            Long existing = idempotency.get(idemKey);

            if (existing != null) {
                rejectOrder(seq, ts, c, RejectReason.DUPLICATE_CLIENT_ORDER_ID, existing, sink);
                return;
            }
        }

        Side side = c.side();
        OrderBook book = books[inst.id()];
        long limit = market ? (side == Side.BUY ? Long.MAX_VALUE : 0L) : c.price();

        if (tif == TimeInForce.POST_ONLY && book.crosses(side, limit)) {
            rejectOrder(seq, ts, c, RejectReason.POST_ONLY_WOULD_CROSS, 0, sink);
            return;
        }

        OrderBook.Preview preview = null;
        if (tif == TimeInForce.FOK || (market && side == Side.BUY)) {
            preview = book.preview(side, limit, qty, c.participantId());
        }

        if (tif == TimeInForce.FOK && preview.fillable() < qty) {
            rejectOrder(seq, ts, c, RejectReason.FOK_NOT_FILLABLE, 0, sink);
            return;
        }

        long holdCash = 0;
        if (side == Side.BUY) {
            holdCash = market ? preview.cost() : c.price() * qty;

            if (!ledger.hold(account, Ledger.CASH, holdCash)) {
                rejectOrder(seq, ts, c, RejectReason.INSUFFICIENT_FUNDS, 0, sink);
                return;
            }
        } else if (!ledger.hold(account, inst.id(), qty)) {
            rejectOrder(seq, ts, c, RejectReason.INSUFFICIENT_POSITION, 0, sink);
            return;
        }

        long orderId = nextOrderId++;
        if (idemKey != null) {
            idempotency.put(idemKey, orderId);
        }

        Order order = new Order(orderId, c.participantId(), inst.id(), side, market ? 0 : c.price(), qty, qty, seq, ts);
        sink.accept(new Event.OrderAccepted(seq, ts, orderId, c.participantId(), inst.id(), clientId, side,
                c.type(), tif, order.price, qty));

        matcher.match(seq, ts, order, book, limit, market, sink);

        long left = order.remaining;
        if (left == 0) {
            return;
        }

        boolean mayRest = !market && !matcher.selfTradePrevented
                && (tif == TimeInForce.GTC || tif == TimeInForce.POST_ONLY);
        if (mayRest) {
            book.add(order);
            resting.put(orderId, order);
            sink.accept(new Event.BookLevelChanged(seq, ts, inst.id(), side, order.price,
                    book.levelQuantity(side, order.price)));
        } else {
            if (side == Side.BUY) {
                ledger.release(account, Ledger.CASH, holdCash - matcher.holdConsumed);
            } else {
                ledger.release(account, inst.id(), left);
            }

            CancelReason reason = matcher.selfTradePrevented ? CancelReason.SELF_TRADE_PREVENTION
                    : market ? CancelReason.MARKET_REMAINDER : CancelReason.IOC_REMAINDER;
            sink.accept(new Event.OrderCancelled(seq, ts, orderId, c.participantId(), inst.id(), left, reason));
        }
    }

    private void rejectOrder(long seq, long ts, Command.PlaceOrder c, RejectReason reason, long existingOrderId,
                             EventSink sink) {
        sink.accept(new Event.OrderRejected(seq, ts, c.participantId(), c.instrumentId(), c.clientOrderId(), reason,
                existingOrderId));
    }

    // ------------------------------------------------------------------ Отмена

    private void cancelOrder(long seq, long ts, Command.CancelOrder c, EventSink sink) {
        Order order = resting.get(c.orderId());

        if (order == null || order.participantId != c.participantId()) {
            sink.accept(new Event.CommandRejected(seq, ts, c.participantId(), RejectReason.UNKNOWN_ORDER, c.orderId()));
            return;
        }

        OrderBook book = books[order.instrumentId];
        Account account = ledger.account(order.participantId);
        long left = order.remaining;

        book.remove(order);
        resting.remove(order.id);

        if (order.side == Side.BUY) {
            ledger.release(account, Ledger.CASH, order.price * left);
        } else {
            ledger.release(account, order.instrumentId, left);
        }

        sink.accept(new Event.OrderCancelled(seq, ts, order.id, order.participantId, order.instrumentId, left,
                CancelReason.USER));
        sink.accept(new Event.BookLevelChanged(seq, ts, order.instrumentId, order.side, order.price,
                book.levelQuantity(order.side, order.price)));
    }

    // ------------------------------------------------------------------ Админ

    private void deposit(long seq, long ts, Command.Deposit c, EventSink sink) {
        RejectReason rejected = ledger.deposit(seq, ts, c.accountId(), c.assetId(), c.amount(), sink);
        if (rejected != null) {
            sink.accept(new Event.CommandRejected(seq, ts, c.accountId(), rejected, c.assetId()));
        }
    }

    private void setHalted(long seq, long ts, Command.SetHalted c, EventSink sink) {
        if (instrument(c.instrumentId()) == null) {
            sink.accept(new Event.CommandRejected(seq, ts, 0, RejectReason.UNKNOWN_INSTRUMENT, c.instrumentId()));
            return;
        }
        halted[c.instrumentId()] = c.halted();
        sink.accept(new Event.TradingStatusChanged(seq, ts, c.instrumentId(), c.halted()));
    }

    // ------------------------------------------------------------------ Восстановление ссостояния из снапшота

    void restoreCounters(long lastSeq, long nextOrderId, long nextTradeId) {
        this.lastSeq = lastSeq;
        this.nextOrderId = nextOrderId;
        this.matcher.nextTradeId = nextTradeId;
    }

    void restoreHalted(int instrumentId, boolean value) {
        halted[instrumentId] = value;
    }

    void restoreOrder(Order order) {
        books[order.instrumentId].add(order);
        resting.put(order.id, order);
    }

    IdempotencyIndex idempotency() {
        return idempotency;
    }
}
