package dev.glassbook.core;

import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ReferenceExchange {

    private static final class Resting {
        final long id;
        final long participant;
        final Side side;
        final long price;
        final long seq;
        long remaining;

        Resting(long id, long participant, Side side, long price, long seq, long remaining) {
            this.id = id;
            this.participant = participant;
            this.side = side;
            this.price = price;
            this.seq = seq;
            this.remaining = remaining;
        }
    }

    private final Map<Integer, List<Resting>> books = new HashMap<>();
    private final List<String> tape = new ArrayList<>();
    private long nextOrderId = 1;
    private long seq;

    public List<String> tape() {
        return tape;
    }

    public void apply(Command command) {
        seq++;
        switch (command) {
            case Command.PlaceOrder c -> place(c);
            case Command.CancelOrder c -> cancel(c);
            case Command.Deposit ignored -> { }
            case Command.SetHalted ignored -> throw new UnsupportedOperationException("not modelled");
        }
    }

    private void place(Command.PlaceOrder c) {
        List<Resting> book = books.computeIfAbsent(c.instrumentId(), k -> new ArrayList<>());
        boolean market = c.type() == OrderType.MARKET;
        TimeInForce tif = market && c.timeInForce() == TimeInForce.GTC ? TimeInForce.IOC : c.timeInForce();
        Side side = c.side();
        long limit = market ? (side == Side.BUY ? Long.MAX_VALUE : 0) : c.price();

        List<Resting> opposite = new ArrayList<>();
        for (Resting r : book) if (r.side != side) opposite.add(r);
        opposite.sort(Comparator.<Resting>comparingLong(r -> side == Side.BUY ? r.price : -r.price)
                .thenComparingLong(r -> r.seq));

        if (tif == TimeInForce.POST_ONLY && !opposite.isEmpty() && acceptable(side, limit, opposite.get(0).price)) {
            tape.add("R POST_ONLY_WOULD_CROSS");
            return;
        }
        if (tif == TimeInForce.FOK) {
            long fillable = 0;
            for (Resting r : opposite) {
                if (!acceptable(side, limit, r.price) || r.participant == c.participantId()) break;
                fillable += Math.min(c.quantity() - fillable, r.remaining);
                if (fillable == c.quantity()) break;
            }
            if (fillable < c.quantity()) {
                tape.add("R FOK_NOT_FILLABLE");
                return;
            }
        }

        long id = nextOrderId++;
        long remaining = c.quantity();
        boolean stp = false;
        for (Resting maker : opposite) {
            if (remaining == 0) break;
            if (!acceptable(side, limit, maker.price)) break;
            if (maker.participant == c.participantId()) {
                stp = true;
                break;
            }
            long qty = Math.min(remaining, maker.remaining);
            long buy = side == Side.BUY ? id : maker.id;
            long sell = side == Side.BUY ? maker.id : id;
            tape.add("T " + c.instrumentId() + " " + maker.price + " " + qty + " " + buy + " " + sell);
            maker.remaining -= qty;
            remaining -= qty;
            if (maker.remaining == 0) book.remove(maker);
        }
        if (remaining > 0) {
            if (!market && !stp && (tif == TimeInForce.GTC || tif == TimeInForce.POST_ONLY)) {
                book.add(new Resting(id, c.participantId(), side, c.price(), seq, remaining));
            } else {
                String reason = stp ? "SELF_TRADE_PREVENTION" : market ? "MARKET_REMAINDER" : "IOC_REMAINDER";
                tape.add("C " + id + " " + remaining + " " + reason);
            }
        }
    }

    private void cancel(Command.CancelOrder c) {
        for (List<Resting> book : books.values()) {
            for (Resting r : book) {
                if (r.id == c.orderId() && r.participant == c.participantId()) {
                    book.remove(r);
                    tape.add("C " + r.id + " " + r.remaining + " USER");
                    return;
                }
            }
        }
        tape.add("R UNKNOWN_ORDER");
    }

    private static boolean acceptable(Side takerSide, long limit, long makerPrice) {
        return takerSide == Side.BUY ? makerPrice <= limit : makerPrice >= limit;
    }

    /** Resting orders per instrument and side, best first, as "id:remaining@price". */
    public List<String> finalBook() {
        List<String> out = new ArrayList<>();
        for (int inst = 1; inst <= 2; inst++) {
            List<Resting> book = books.getOrDefault(inst, List.of());
            for (Side side : Side.values()) {
                List<Resting> sorted = new ArrayList<>();
                for (Resting r : book) if (r.side == side) sorted.add(r);
                sorted.sort(Comparator.<Resting>comparingLong(r -> side == Side.SELL ? r.price : -r.price)
                        .thenComparingLong(r -> r.seq));
                for (Resting r : sorted) out.add(inst + " " + side + " " + r.id + ":" + r.remaining + "@" + r.price);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ translate engine events into the same tape

    public static List<String> tapeOf(List<Event> events) {
        List<String> out = new ArrayList<>();
        for (Event e : events) {
            switch (e) {
                case Event.TradeExecuted t -> out.add("T " + t.instrumentId() + " " + t.price() + " " + t.quantity()
                        + " " + t.buyOrderId() + " " + t.sellOrderId());
                case Event.OrderCancelled c -> out.add("C " + c.orderId() + " " + c.remainingQuantity() + " " + c.reason());
                case Event.OrderRejected r -> out.add("R " + r.reason());
                case Event.CommandRejected r -> out.add("R " + r.reason());
                default -> { }
            }
        }
        return out;
    }
}
