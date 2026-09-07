package dev.glassbook.core;

import dev.glassbook.core.model.CancelReason;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.RejectReason;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.glassbook.core.TestExchange.cancelled;
import static dev.glassbook.core.TestExchange.orderId;
import static dev.glassbook.core.TestExchange.rejection;
import static dev.glassbook.core.TestExchange.trades;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class MatchingScenariosTest {

    private static final long CASH = 10_000_000;

    private long vikaAsk;
    private long borisAsk;
    private long glebBid;

    private TestExchange glass() {
        TestExchange t = new TestExchange();
        for (long seller = 1; seller <= 3; seller++) t.fund(seller, 0, 1_000);
        t.fund(4, CASH, 0);
        t.fund(5, CASH, 0);
        t.fund(9, CASH, 1_000);
        vikaAsk = orderId(t.limit(1, Side.SELL, 10_100, 100));
        borisAsk = orderId(t.limit(2, Side.SELL, 10_120, 200));
        t.limit(3, Side.SELL, 10_150, 300);
        glebBid = orderId(t.limit(4, Side.BUY, 10_080, 150));
        t.limit(5, Side.BUY, 10_050, 400);
        t.checkInvariants();
        return t;
    }

    @Test
    @DisplayName("market buy walks the book: best price first, execution at the maker's price")
    void marketBuyWalksTheBook() {
        TestExchange t = glass();

        List<Event> out = t.market(9, Side.BUY, 250);

        List<Event.TradeExecuted> fills = trades(out);
        assertEquals(2, fills.size());
        assertEquals(10_100, fills.get(0).price());
        assertEquals(100, fills.get(0).quantity());
        assertEquals(vikaAsk, fills.get(0).sellOrderId());
        assertEquals(10_120, fills.get(1).price());
        assertEquals(150, fills.get(1).quantity());
        assertEquals(borisAsk, fills.get(1).sellOrderId());

        long cost = 100 * 10_100 + 150 * 10_120;                 // 2 528 000
        assertEquals(CASH - cost, t.free(9, 0));
        assertEquals(0, t.held(9, 0));
        assertEquals(1_250, t.free(9, 1));
        assertEquals(cost - 150 * 10_120, t.free(1, 0));          // Vika sold everything she offered
        assertEquals(0, t.held(1, 1));
        assertEquals(50, t.held(2, 1));                            // Boris still has 50 on offer
        assertEquals(50, t.exchange.book(1).levelQuantity(Side.SELL, 10_120));
        assertEquals(0, t.exchange.book(1).levelQuantity(Side.SELL, 10_100));
        t.checkInvariants();
    }

    @Test
    @DisplayName("a limit order that does not cross waits in the queue behind earlier orders")
    void nonCrossingLimitRests() {
        TestExchange t = glass();

        List<Event> out = t.limit(9, Side.BUY, 10_080, 120);

        assertTrue(trades(out).isEmpty());
        assertEquals(270, t.exchange.book(1).levelQuantity(Side.BUY, 10_080));
        assertEquals(10_080L * 120, t.held(9, 0));
        t.checkInvariants();
    }

    @Test
    @DisplayName("price-time priority: at equal prices the older order trades first")
    void fifoWithinLevel() {
        TestExchange t = glass();
        long second = orderId(t.limit(2, Side.SELL, 10_100, 100));   // same price as Vika, later

        List<Event.TradeExecuted> fills = trades(t.limit(9, Side.BUY, 10_100, 150));

        assertEquals(2, fills.size());
        assertEquals(vikaAsk, fills.get(0).sellOrderId());
        assertEquals(100, fills.get(0).quantity());
        assertEquals(second, fills.get(1).sellOrderId());
        assertEquals(50, fills.get(1).quantity());
        t.checkInvariants();
    }

    @Test
    @DisplayName("price improvement: a buyer who bids 102.00 pays 101.00 and gets the difference back")
    void priceImprovementIsRefunded() {
        TestExchange t = glass();

        t.limit(9, Side.BUY, 10_200, 100);

        assertEquals(CASH - 100 * 10_100, t.free(9, 0));
        assertEquals(0, t.held(9, 0));
        t.checkInvariants();
    }

    @Test
    @DisplayName("partial fill: the unfilled remainder rests at the limit price and keeps its reservation")
    void partialFillRests() {
        TestExchange t = glass();

        List<Event> out = t.limit(9, Side.BUY, 10_100, 150);

        assertEquals(1, trades(out).size());
        assertEquals(50, t.exchange.book(1).levelQuantity(Side.BUY, 10_100));
        assertEquals(10_100L * 50, t.held(9, 0));
        t.checkInvariants();
    }

    @Test
    @DisplayName("IOC fills what it can and cancels the rest")
    void iocCancelsRemainder() {
        TestExchange t = glass();

        List<Event> out = t.limit(9, Side.BUY, 10_100, 150, TimeInForce.IOC, "");

        assertEquals(100, trades(out).get(0).quantity());
        assertEquals(CancelReason.IOC_REMAINDER, cancelled(out).orElseThrow().reason());
        assertEquals(50, cancelled(out).orElseThrow().remainingQuantity());
        assertEquals(0, t.exchange.book(1).levelQuantity(Side.BUY, 10_100));
        assertEquals(0, t.held(9, 0));
        t.checkInvariants();
    }

    @Test
    @DisplayName("FOK: all or nothing")
    void fillOrKill() {
        TestExchange t = glass();

        List<Event> tooBig = t.limit(9, Side.BUY, 10_120, 301, TimeInForce.FOK, "");
        assertEquals(RejectReason.FOK_NOT_FILLABLE, rejection(tooBig).orElseThrow());
        assertTrue(trades(tooBig).isEmpty());
        assertEquals(CASH, t.free(9, 0));
        assertEquals(300, t.exchange.book(1).levelQuantity(Side.SELL, 10_100)
                + t.exchange.book(1).levelQuantity(Side.SELL, 10_120));

        List<Event> exact = t.limit(9, Side.BUY, 10_120, 300, TimeInForce.FOK, "");
        assertEquals(2, trades(exact).size());
        assertEquals(0, t.held(9, 0));
        t.checkInvariants();
    }

    @Test
    @DisplayName("POST_ONLY never takes liquidity")
    void postOnly() {
        TestExchange t = glass();

        assertEquals(RejectReason.POST_ONLY_WOULD_CROSS,
                rejection(t.limit(9, Side.BUY, 10_100, 10, TimeInForce.POST_ONLY, "")).orElseThrow());
        assertTrue(rejection(t.limit(9, Side.BUY, 10_090, 10, TimeInForce.POST_ONLY, "")).isEmpty());
        assertEquals(10, t.exchange.book(1).levelQuantity(Side.BUY, 10_090));
        t.checkInvariants();
    }

    @Test
    @DisplayName("self-trade prevention cancels the incoming order and leaves the resting one alone")
    void selfTradePrevention() {
        TestExchange t = glass();
        t.fund(1, CASH, 0);

        List<Event> out = t.limit(1, Side.BUY, 10_100, 50);   // Vika would buy from herself

        assertTrue(trades(out).isEmpty());
        assertEquals(CancelReason.SELF_TRADE_PREVENTION, cancelled(out).orElseThrow().reason());
        assertEquals(100, t.exchange.book(1).levelQuantity(Side.SELL, 10_100));
        assertEquals(0, t.held(1, 0));
        t.checkInvariants();
    }

    @Test
    @DisplayName("market order on a thin book: fills what exists, cancels the rest, reserves only what it needs")
    void marketBuyRunsOutOfLiquidity() {
        TestExchange t = glass();

        List<Event> out = t.market(9, Side.BUY, 700);          // only 600 offered in total

        assertEquals(600, trades(out).stream().mapToLong(Event.TradeExecuted::quantity).sum());
        assertEquals(CancelReason.MARKET_REMAINDER, cancelled(out).orElseThrow().reason());
        assertEquals(100, cancelled(out).orElseThrow().remainingQuantity());
        assertEquals(0, t.held(9, 0));
        t.checkInvariants();
    }

    @Test
    @DisplayName("market buy reserves the exact cost of the walk: one kopeck short is rejected")
    void marketBuyReservationIsExact() {
        TestExchange t = glass();
        long cost = 100 * 10_100 + 150 * 10_120;
        t.fund(7, cost - 1, 0);
        t.fund(8, cost, 0);

        assertEquals(RejectReason.INSUFFICIENT_FUNDS, rejection(t.market(7, Side.BUY, 250)).orElseThrow());
        assertTrue(rejection(t.market(8, Side.BUY, 250)).isEmpty());
        assertEquals(0, t.free(8, 0));
        t.checkInvariants();
    }

    @Test
    @DisplayName("market order on an empty book is accepted and immediately cancelled")
    void marketOnEmptyBook() {
        TestExchange t = new TestExchange();
        t.fund(1, CASH, 100);

        List<Event> out = t.market(1, Side.SELL, 10);

        assertTrue(trades(out).isEmpty());
        assertEquals(CancelReason.MARKET_REMAINDER, cancelled(out).orElseThrow().reason());
        assertEquals(100, t.free(1, 1));
        t.checkInvariants();
    }

    @Test
    @DisplayName("cancel releases the reservation; foreign or unknown orders are reported as unknown")
    void cancelOrders() {
        TestExchange t = glass();
        long held = t.held(4, 0);
        assertEquals(10_080L * 150, held);

        assertEquals(RejectReason.UNKNOWN_ORDER, rejection(t.cancel(5, glebBid)).orElseThrow());   // not Dima's
        assertEquals(RejectReason.UNKNOWN_ORDER, rejection(t.cancel(4, 99_999)).orElseThrow());
        assertEquals(150, t.exchange.book(1).levelQuantity(Side.BUY, 10_080));

        List<Event> out = t.cancel(4, glebBid);

        assertEquals(CancelReason.USER, cancelled(out).orElseThrow().reason());
        assertEquals(0, t.held(4, 0));
        assertEquals(CASH, t.free(4, 0));
        assertEquals(0, t.exchange.book(1).levelQuantity(Side.BUY, 10_080));
        assertEquals(RejectReason.UNKNOWN_ORDER, rejection(t.cancel(4, glebBid)).orElseThrow());     // twice
        t.checkInvariants();
    }

    @Test
    @DisplayName("risk: not enough cash or shares means rejection and no state change")
    void insufficientBalances() {
        TestExchange t = glass();
        t.fund(7, 1_000, 5);
        long ordersBefore = t.exchange.restingOrderCount();

        assertEquals(RejectReason.INSUFFICIENT_FUNDS, rejection(t.limit(7, Side.BUY, 10_100, 100)).orElseThrow());
        assertEquals(RejectReason.INSUFFICIENT_POSITION, rejection(t.limit(7, Side.SELL, 10_300, 6)).orElseThrow());

        assertEquals(ordersBefore, t.exchange.restingOrderCount());
        assertEquals(1_000, t.free(7, 0));
        assertEquals(5, t.free(7, 1));
        assertEquals(0, t.held(7, 0));
        t.checkInvariants();
    }

    @Test
    @DisplayName("client order id makes a retry harmless")
    void idempotentClientOrderId() {
        TestExchange t = glass();

        long first = orderId(t.limit(9, Side.BUY, 10_000, 10, TimeInForce.GTC, "retry-me"));
        List<Event> second = t.limit(9, Side.BUY, 10_000, 10, TimeInForce.GTC, "retry-me");

        Event.OrderRejected rejected = (Event.OrderRejected) second.get(0);
        assertEquals(RejectReason.DUPLICATE_CLIENT_ORDER_ID, rejected.reason());
        assertEquals(first, rejected.existingOrderId());
        assertEquals(10, t.exchange.book(1).levelQuantity(Side.BUY, 10_000));
        assertEquals(10_000L * 10, t.held(9, 0));
        // the key is scoped to the participant
        t.fund(8, CASH, 0);
        assertTrue(rejection(t.limit(8, Side.BUY, 10_000, 10, TimeInForce.GTC, "retry-me")).isEmpty());
        t.checkInvariants();
    }

    @Test
    @DisplayName("halted instrument: no new orders, cancels still work, resume reopens")
    void halt() {
        TestExchange t = glass();

        t.apply(new Command.SetHalted(1, true));
        assertEquals(RejectReason.INSTRUMENT_HALTED, rejection(t.limit(9, Side.BUY, 10_000, 1)).orElseThrow());
        assertTrue(rejection(t.cancel(4, glebBid)).isEmpty());

        t.apply(new Command.SetHalted(1, false));
        assertTrue(rejection(t.limit(9, Side.BUY, 10_000, 1)).isEmpty());
        t.checkInvariants();
    }

    @Test
    @DisplayName("tick size, lot size and malformed orders are rejected")
    void validation() {
        TestExchange t = glass();
        t.apply(new Command.Deposit(9, 2, 1_000));

        assertEquals(RejectReason.TICK_SIZE_VIOLATION, rejection(t.apply(new Command.PlaceOrder(
                9, 2, "", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 5_003, 10))).orElseThrow());
        assertEquals(RejectReason.LOT_SIZE_VIOLATION, rejection(t.apply(new Command.PlaceOrder(
                9, 2, "", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 5_000, 7))).orElseThrow());
        assertEquals(RejectReason.INVALID_QUANTITY, rejection(t.limit(9, Side.BUY, 10_000, 0)).orElseThrow());
        assertEquals(RejectReason.INVALID_PRICE, rejection(t.limit(9, Side.BUY, 0, 1)).orElseThrow());
        assertEquals(RejectReason.INVALID_PRICE, rejection(t.apply(new Command.PlaceOrder(
                9, 1, "", Side.BUY, OrderType.MARKET, TimeInForce.IOC, 10_000, 1))).orElseThrow());
        assertEquals(RejectReason.INVALID_ORDER, rejection(t.apply(new Command.PlaceOrder(
                9, 1, "", Side.BUY, OrderType.MARKET, TimeInForce.POST_ONLY, 0, 1))).orElseThrow());
        assertEquals(RejectReason.UNKNOWN_INSTRUMENT, rejection(t.apply(new Command.PlaceOrder(
                9, 7, "", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 1, 1))).orElseThrow());
        assertEquals(RejectReason.UNKNOWN_ACCOUNT, rejection(t.limit(1234, Side.BUY, 10_000, 1)).orElseThrow());
        t.checkInvariants();
    }

    @Test
    @DisplayName("deposits are validated and cannot overflow a balance")
    void depositLimits() {
        TestExchange t = new TestExchange();

        assertEquals(RejectReason.INVALID_AMOUNT, rejection(t.apply(new Command.Deposit(1, 0, 0))).orElseThrow());
        assertEquals(RejectReason.INVALID_AMOUNT, rejection(t.apply(new Command.Deposit(1, 0, -5))).orElseThrow());
        assertEquals(RejectReason.INVALID_AMOUNT, rejection(t.apply(new Command.Deposit(1, 5, 5))).orElseThrow());
        assertEquals(RejectReason.INVALID_AMOUNT, rejection(t.apply(new Command.Deposit(0, 0, 5))).orElseThrow());
        long deposits = 0;
        while (rejection(t.apply(new Command.Deposit(1, 0, 1_000_000_000_000_000L))).isEmpty()) {
            if (++deposits > 10_000) throw new AssertionError("overflow guard never triggered");
        }
        assertEquals(RejectReason.BALANCE_OVERFLOW,
                rejection(t.apply(new Command.Deposit(1, 0, 1_000_000_000_000_000L))).orElseThrow());
        t.checkInvariants();
    }

    @Test
    @DisplayName("every posting group is balanced and trades emit four legs")
    void postingsBalance() {
        TestExchange t = glass();
        t.market(9, Side.BUY, 250);

        long posted = t.events.stream().filter(Event.Posted.class::isInstance).map(Event.Posted.class::cast)
                .mapToLong(Event.Posted::delta).sum();
        assertEquals(0, posted);
        assertFalse(t.events.stream().noneMatch(Event.Posted.class::isInstance));
    }

    @Test
    @DisplayName("the state machine refuses out-of-order sequence numbers")
    void sequenceGap() {
        TestExchange t = new TestExchange();
        assertThrows(IllegalStateException.class, () ->
                t.exchange.apply(42, 0, new Command.Deposit(1, 0, 1), e -> { }));
    }
}
