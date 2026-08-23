package dev.glassbook.core.book;

import dev.glassbook.core.model.Instrument;
import dev.glassbook.core.model.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;


public final class OrderBook {

    public record Preview(long fillable, long cost) { }

    private final Instrument instrument;
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    private int orderCount;

    public OrderBook(Instrument instrument) {
        this.instrument = instrument;
    }

    public Instrument instrument() {
        return instrument;
    }

    public int orderCount() {
        return orderCount;
    }

    private TreeMap<Long, PriceLevel> side(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    public void add(Order o) {
        if (o.level != null) {
            throw new IllegalStateException("Заявка уже добавлена: " + o);
        }

        side(o.side).computeIfAbsent(o.price, PriceLevel::new).append(o);
        orderCount++;
    }

    public void remove(Order o) {
        PriceLevel level = o.level;
        if (level == null) {
            throw new IllegalStateException("Заявка не завершена: " + o);
        }

        level.unlink(o);
        if (level.isEmpty()) {
            side(o.side).remove(level.price);
        }
        orderCount--;
    }

    public void fill(Order maker, long qty) {
        if (qty <= 0 || qty > maker.remaining) {
            throw new IllegalArgumentException("Неверное значение  " + qty + " для " + maker);
        }

        maker.remaining -= qty;
        maker.level.totalQuantity -= qty;

        if (maker.remaining == 0) {
            remove(maker);
        }
    }

    public PriceLevel bestLevel(Side side) {
        Map.Entry<Long, PriceLevel> e = side(side).firstEntry();
        return e == null ? null : e.getValue();
    }

    public Order bestOrder(Side side) {
        PriceLevel level = bestLevel(side);
        return level == null ? null : level.head;
    }

    public boolean hasBest(Side side) {
        return !side(side).isEmpty();
    }

    public long bestPrice(Side side) {
        return side(side).firstKey();
    }

    public long levelQuantity(Side side, long price) {
        PriceLevel level = side(side).get(price);
        return level == null ? 0 : level.totalQuantity;
    }

    /// Используется при POST_ONLY
    public boolean crosses(Side takerSide, long limit) {
        Side opposite = takerSide.opposite();

        if (!hasBest(opposite)) {
            return false;
        }
        return priceAcceptable(takerSide, limit, bestPrice(opposite));
    }

    public static boolean priceAcceptable(Side takerSide, long limit, long makerPrice) {
        return takerSide == Side.BUY ? makerPrice <= limit : makerPrice >= limit;
    }

    /// Для FOK
    public Preview preview(Side takerSide, long limit, long quantity, long participantId) {
        long left = quantity;
        long cost = 0;
        outer:

        for (PriceLevel level : side(takerSide.opposite()).values()) {
            if (!priceAcceptable(takerSide, limit, level.price)) break;
            for (Order o = level.head; o != null; o = o.next) {
                if (o.participantId == participantId) break outer;
                long q = Math.min(left, o.remaining);
                cost += level.price * q;
                left -= q;
                if (left == 0) break outer;
            }
        }
        return new Preview(quantity - left, cost);
    }

    public List<DepthLevel> depth(Side side, int maxLevels) {
        List<DepthLevel> out = new ArrayList<>(Math.min(maxLevels, 64));
        for (PriceLevel level : side(side).values()) {
            if (out.size() >= maxLevels) break;
            out.add(new DepthLevel(level.price, level.totalQuantity, level.orderCount));
        }
        return out;
    }

    public void forEachOrder(Side side, Consumer<Order> visitor) {
        for (PriceLevel level : side(side).values()) {
            for (Order o = level.head; o != null; o = o.next) {
                visitor.accept(o);
            }
        }
    }

    public Iterator<PriceLevel> levels(Side side) {
        return side(side).values().iterator();
    }
}
