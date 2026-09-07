package dev.glassbook.core;

import dev.glassbook.core.engine.Exchange;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.Instrument;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.RejectReason;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TestExchange {

    public static final Instrument ABC = new Instrument(1, "ABC", 1, 1, 2);
    public static final Instrument XYZ = new Instrument(2, "XYZ", 5, 10, 2);
    public static final List<Instrument> INSTRUMENTS = List.of(ABC, XYZ);

    public final Exchange exchange;
    public final List<Event> events = new ArrayList<>();
    public final long[] deposited = new long[3];
    private long seq;
    private long ts = 1_000;

    public TestExchange() {
        this(INSTRUMENTS);
    }

    public TestExchange(List<Instrument> instruments) {
        this.exchange = new Exchange(instruments, 10_000);
    }

    public List<Event> apply(Command command) {
        int from = events.size();
        seq++;
        ts++;
        exchange.apply(seq, ts, command, events::add);
        List<Event> produced = new ArrayList<>(events.subList(from, events.size()));
        if (command instanceof Command.Deposit d
                && produced.stream().noneMatch(Event.CommandRejected.class::isInstance)) {
            deposited[d.assetId()] += d.amount();
        }
        return produced;
    }

    public void fund(long participant, long cash, long abcShares) {
        if (cash > 0) apply(new Command.Deposit(participant, 0, cash));
        if (abcShares > 0) apply(new Command.Deposit(participant, 1, abcShares));
    }

    public List<Event> limit(long participant, Side side, long price, long qty) {
        return limit(participant, side, price, qty, TimeInForce.GTC, "");
    }

    public List<Event> limit(long participant, Side side, long price, long qty, TimeInForce tif, String clientId) {
        return apply(new Command.PlaceOrder(participant, 1, clientId, side, OrderType.LIMIT, tif, price, qty));
    }

    public List<Event> market(long participant, Side side, long qty) {
        return apply(new Command.PlaceOrder(participant, 1, "", side, OrderType.MARKET, TimeInForce.IOC, 0, qty));
    }

    public List<Event> cancel(long participant, long orderId) {
        return apply(new Command.CancelOrder(participant, orderId));
    }

    // ------------------------------------------------------------------ result helpers

    public static long orderId(List<Event> produced) {
        return produced.stream().filter(Event.OrderAccepted.class::isInstance).map(Event.OrderAccepted.class::cast)
                .mapToLong(Event.OrderAccepted::orderId).findFirst().orElseThrow(() -> new AssertionError(
                        "order was not accepted: " + produced));
    }

    public static List<Event.TradeExecuted> trades(List<Event> produced) {
        return produced.stream().filter(Event.TradeExecuted.class::isInstance).map(Event.TradeExecuted.class::cast)
                .toList();
    }

    public static Optional<RejectReason> rejection(List<Event> produced) {
        for (Event e : produced) {
            if (e instanceof Event.OrderRejected r) return Optional.of(r.reason());
            if (e instanceof Event.CommandRejected r) return Optional.of(r.reason());
        }
        return Optional.empty();
    }

    public static Optional<Event.OrderCancelled> cancelled(List<Event> produced) {
        return produced.stream().filter(Event.OrderCancelled.class::isInstance).map(Event.OrderCancelled.class::cast)
                .findFirst();
    }

    public long free(long participant, int asset) {
        return exchange.ledger().account(participant).free(asset);
    }

    public long held(long participant, int asset) {
        return exchange.ledger().account(participant).held(asset);
    }

    public void checkInvariants() {
        Invariants.check(exchange, deposited, events);
    }
}
