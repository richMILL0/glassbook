package dev.glassbook.core;

import dev.glassbook.core.book.DepthLevel;
import dev.glassbook.core.book.OrderBook;
import dev.glassbook.core.engine.Exchange;
import dev.glassbook.core.ledger.Account;
import dev.glassbook.core.ledger.Ledger;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.Side;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Invariants {

    private Invariants() {}

    public static void check(Exchange x, long[] deposited, List<Event> allEvents) {
        Ledger ledger = x.ledger();
        int assets = ledger.assetCount();


        Map<Long, long[]> expectedHeld = new HashMap<>();
        int totalResting = 0;

        for (int id = 1; id <= x.instrumentCount(); id++) {
            OrderBook book = x.book(id);


            if (book.hasBest(Side.BUY) && book.hasBest(Side.SELL)) {
                require(book.bestPrice(Side.BUY) < book.bestPrice(Side.SELL),
                        "crossed book on " + id + ": bid " + book.bestPrice(Side.BUY) + " ask " + book.bestPrice(Side.SELL));
            }

            for (Side side : Side.values()) {
                Map<Long, long[]> perLevel = new LinkedHashMap<>();
                final int bookId = id;
                book.forEachOrder(side, o -> {
                    require(o.remaining > 0 && o.remaining <= o.quantity, "bad remaining on " + o);
                    require(o.side == side && o.instrumentId == bookId, "order on wrong side/book: " + o);
                    require(x.restingOrder(o.id) == o, "resting index does not know " + o);
                    long[] agg = perLevel.computeIfAbsent(o.price, k -> new long[]{0, 0, -1});
                    require(o.seq > agg[2], "FIFO violated at price " + o.price + " by " + o);
                    agg[0] += o.remaining;
                    agg[1]++;
                    agg[2] = o.seq;
                    long[] held = expectedHeld.computeIfAbsent(o.participantId, k -> new long[assets]);
                    if (side == Side.BUY) held[Ledger.CASH] += o.price * o.remaining;
                    else held[bookId] += o.remaining;
                });

                List<DepthLevel> depth = book.depth(side, Integer.MAX_VALUE);
                require(depth.size() == perLevel.size(), "level count mismatch on " + id + " " + side);
                long previous = Long.MIN_VALUE;
                int i = 0;
                for (Map.Entry<Long, long[]> e : perLevel.entrySet()) {
                    DepthLevel level = depth.get(i++);
                    require(level.price() == e.getKey(), "level price order mismatch");
                    require(level.quantity() == e.getValue()[0], "level quantity mismatch at " + level.price());
                    require(level.orders() == e.getValue()[1], "level order count mismatch at " + level.price());
                    if (previous != Long.MIN_VALUE) {
                        require(side == Side.SELL ? level.price() > previous : level.price() < previous,
                                "levels out of order on " + id + " " + side);
                    }
                    previous = level.price();
                    totalResting += (int) e.getValue()[1];
                }
            }
        }
        require(totalResting == x.restingOrderCount(),
                "resting index size " + x.restingOrderCount() + " != orders in books " + totalResting);

        long[] sums = new long[assets];
        for (Account a : ledger.accounts().values()) {
            for (int asset = 0; asset < assets; asset++) {
                sums[asset] += a.total(asset);
                if (a.id() == Ledger.SYSTEM) continue;
                require(a.free(asset) >= 0, "negative free balance: account " + a.id() + " asset " + asset);
                require(a.held(asset) >= 0, "negative held balance: account " + a.id() + " asset " + asset);
                long expected = expectedHeld.getOrDefault(a.id(), new long[assets])[asset];
                require(a.held(asset) == expected, "held mismatch: account " + a.id() + " asset " + asset
                        + " held " + a.held(asset) + " but resting orders need " + expected);
            }
        }

        for (int asset = 0; asset < assets; asset++) {
            require(sums[asset] == 0, "asset " + asset + " does not sum to zero: " + sums[asset]);
            if (deposited != null && asset < deposited.length) {
                require(-ledger.account(Ledger.SYSTEM).total(asset) == deposited[asset],
                        "issued amount of asset " + asset + " differs from deposits");
            }
        }

        if (allEvents != null) {
            long[] posted = new long[assets];
            for (Event e : allEvents) {
                if (e instanceof Event.Posted p) posted[p.assetId()] += p.delta();
            }
            for (int asset = 0; asset < assets; asset++) {
                require(posted[asset] == 0, "postings of asset " + asset + " do not net to zero: " + posted[asset]);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
