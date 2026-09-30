package dev.glassbook.server.market;

import java.util.List;

public final class Views {

    private Views() { }

    public record LevelView(long price, long quantity) { }

    public record BookView(String symbol, long seq, List<LevelView> bids, List<LevelView> asks) { }

    public record TradeView(long id, long price, long quantity, String takerSide, long timestamp) { }

    public record CandleView(long time, long open, long high, long low, long close, long volume) { }

    public record TickerView(String symbol, String name, long last, long bestBid, long bestAsk, long sessionOpen,
                             long volume, boolean halted, int priceScale, long tickSize, long lotSize) { }
}
