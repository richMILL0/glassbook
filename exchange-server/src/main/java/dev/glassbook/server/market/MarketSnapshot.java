package dev.glassbook.server.market;

import dev.glassbook.core.book.DepthLevel;
import dev.glassbook.core.engine.Exchange;
import dev.glassbook.core.model.Side;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record MarketSnapshot(long seq, Map<Integer, List<DepthLevel>> bids, Map<Integer, List<DepthLevel>> asks,
                             Map<Integer, Boolean> halted) {

    public static MarketSnapshot capture(Exchange x) {
        Map<Integer, List<DepthLevel>> bids = new HashMap<>();
        Map<Integer, List<DepthLevel>> asks = new HashMap<>();
        Map<Integer, Boolean> halted = new HashMap<>();
        for (int id = 1; id <= x.instrumentCount(); id++) {
            bids.put(id, x.book(id).depth(Side.BUY, Integer.MAX_VALUE));
            asks.put(id, x.book(id).depth(Side.SELL, Integer.MAX_VALUE));
            halted.put(id, x.isHalted(id));
        }
        return new MarketSnapshot(x.lastSeq(), bids, asks, halted);
    }
}
