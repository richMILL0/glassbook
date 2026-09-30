package dev.glassbook.server.market;

import dev.glassbook.core.book.DepthLevel;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.Instrument;
import dev.glassbook.core.model.Side;
import dev.glassbook.server.config.InstrumentCatalog;
import dev.glassbook.server.market.Views.BookView;
import dev.glassbook.server.market.Views.CandleView;
import dev.glassbook.server.market.Views.LevelView;
import dev.glassbook.server.market.Views.TickerView;
import dev.glassbook.server.market.Views.TradeView;
import dev.glassbook.server.pipeline.EventGroup;
import dev.glassbook.server.projection.TradeRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class MarketDataService {

    public static final int[] INTERVALS = {1, 5, 15, 60};  /// Для графиков разного масштаба
    private static final int MAX_TRADES = 300;             /// Более поздние заявки отбрасываются
    private static final int MAX_CANDLES = 600;            /// Максимальное количество хранящихся свечей

    private static final class Candle {
        long time;
        long open;
        long high;
        long low;
        long close;
        long volume;

        CandleView view() {
            return new CandleView(time, open, high, low, close, volume);
        }
    }

    /// Состояние каждой бумаги
    private static final class State {
        final Instrument instrument;
        final String name;
        final TreeMap<Long, Long> bids = new TreeMap<>(Comparator.reverseOrder());      /// От большей к меньшей
        final TreeMap<Long, Long> asks = new TreeMap<>();                               /// От меньшей к большей
        final ArrayDeque<TradeView> trades = new ArrayDeque<>();                        /// Трейды
        final List<TreeMap<Long, Candle>> candles = new ArrayList<>();                  /// Свечи для разных масштабов
        final List<TradeView> pendingTrades = new ArrayList<>();                        /// Последние 300 сделок
        long last;                                                                      /// Последняя цена
        long sessionOpen;                                                               /// Открытие сессии
        long volume;                                                                    /// Объем
        long lastTradeId;                                                               /// Последнее трейд-id
        boolean halted;                                                                 /// Остановка торгов
        boolean bookDirty;                                                              /// Изменилось ли книга после с прошлой рассылки
        boolean candlesDirty;                                                           /// Изменилась ли свеча после прошлой рассылки

        State(Instrument instrument, String name) {
            this.instrument = instrument;
            this.name = name;
            for (int i = 0; i < INTERVALS.length; i++) candles.add(new TreeMap<>());
        }
    }

    public record Update(String symbol, BookView book, List<TradeView> trades, Map<Integer, CandleView> candles) { }
    private final Object lock = new Object();
    private final Map<Integer, State> byId = new LinkedHashMap<>();
    private final Map<String, State> bySymbol = new LinkedHashMap<>();
    private final Map<String, Counter> tradeCounters = new HashMap<>();
    private final Map<String, Counter> volumeCounters = new HashMap<>();
    private long appliedSeq;

    public MarketDataService(InstrumentCatalog catalog, MeterRegistry meters) {
        for (InstrumentCatalog.Entry e : catalog.entries()) {
            State s = new State(e.instrument(), e.name());
            byId.put(e.instrument().id(), s);
            bySymbol.put(e.instrument().symbol(), s);
            tradeCounters.put(e.instrument().symbol(), Counter.builder("glassbook.trades")
                    .tag("symbol", e.instrument().symbol()).register(meters));
            volumeCounters.put(e.instrument().symbol(), Counter.builder("glassbook.trade.volume")
                    .tag("symbol", e.instrument().symbol()).register(meters));
        }
    }

    // ------------------------------------------------------------------ Запись

    public void apply(EventGroup group) {
        synchronized (lock) {
            for (Event e : group.events()) {
                switch (e) {
                    case Event.BookLevelChanged b -> {
                        if (b.seq() <= appliedSeq) {
                            break;
                        }

                        State s = byId.get(b.instrumentId());
                        TreeMap<Long, Long> side = b.side() == Side.BUY ? s.bids : s.asks;

                        if (b.totalQuantity() == 0) {
                            side.remove(b.price());
                        } else {
                            side.put(b.price(), b.totalQuantity());
                        }

                        s.bookDirty = true;
                    }

                    case Event.TradeExecuted t -> {
                        State s = byId.get(t.instrumentId());
                        if (t.tradeId() <= s.lastTradeId) {
                            break;
                        }
                        addTrade(s, t.tradeId(), t.price(), t.quantity(), t.takerSide().name(), t.timestamp(), true);
                    }

                    case Event.TradingStatusChanged h -> {
                        if (h.seq() > appliedSeq) {
                            byId.get(h.instrumentId()).halted = h.halted();
                        }
                    }
                    default -> { }
                }
            }
            if (group.seq() > appliedSeq) {
                appliedSeq = group.seq();
            }
        }
    }

    /// Замена состояния стаканов на последнее из снапшота
    public void initialize(MarketSnapshot snapshot, Map<Integer, List<TradeRecord>> recentTradesOldestFirst) {
        synchronized (lock) {
            for (Map.Entry<Integer, State> entry : byId.entrySet()) {
                int id = entry.getKey();
                State s = entry.getValue();

                s.bids.clear();
                s.asks.clear();

                for (DepthLevel l : snapshot.bids().getOrDefault(id, List.of())) s.bids.put(l.price(), l.quantity());
                for (DepthLevel l : snapshot.asks().getOrDefault(id, List.of())) s.asks.put(l.price(), l.quantity());
                s.halted = snapshot.halted().getOrDefault(id, false);
                s.trades.clear();
                s.pendingTrades.clear();
                s.candles.forEach(TreeMap::clear);
                s.last = 0;
                s.sessionOpen = 0;
                s.volume = 0;
                s.lastTradeId = 0;

                for (TradeRecord t : recentTradesOldestFirst.getOrDefault(id, List.of())) {
                    addTrade(s, t.getId(), t.getPrice(), t.getQuantity(), t.getTakerSide(),
                            t.getExecutedAt().toEpochMilli(), false);
                }
                s.bookDirty = true;
            }
            appliedSeq = snapshot.seq();
        }
    }

    private void addTrade(State s, long id, long price, long quantity, String takerSide, long timestamp,
                          boolean live) {
        TradeView view = new TradeView(id, price, quantity, takerSide, timestamp);
        s.trades.addFirst(view);

        while (s.trades.size() > MAX_TRADES) {
            s.trades.removeLast();
        }

        s.last = price;
        if (s.sessionOpen == 0) {
            s.sessionOpen = price;
        }
        s.volume += quantity;
        s.lastTradeId = id;

        long seconds = timestamp / 1000;
        for (int i = 0 ; i < INTERVALS.length ; ++i) {
            long bucket = (seconds / INTERVALS[i]) * INTERVALS[i];
            TreeMap<Long, Candle> series = s.candles.get(i);
            Candle c = series.get(bucket);

            if (c == null) {
                c = new Candle();
                c.time = bucket;
                c.open = price;
                c.high = price;
                c.low = price;
                series.put(bucket, c);
                while (series.size() > MAX_CANDLES) series.pollFirstEntry();
            }

            c.high = Math.max(c.high, price);
            c.low = Math.min(c.low, price);
            c.close = price;
            c.volume += quantity;
        }
        if (live) {
            s.pendingTrades.add(view);
            s.candlesDirty = true;
            String symbol = s.instrument.symbol();
            tradeCounters.get(symbol).increment();
            volumeCounters.get(symbol).increment(quantity);
        }
    }

    // ------------------------------------------------------------------ reading

    public List<TickerView> tickers() {
        synchronized (lock) {
            List<TickerView> out = new ArrayList<>();
            for (State s : bySymbol.values()) out.add(ticker(s));
            return out;
        }
    }

    public TickerView ticker(String symbol) {
        synchronized (lock) {
            State s = bySymbol.get(symbol);
            return s == null ? null : ticker(s);
        }
    }

    private TickerView ticker(State s) {
        return new TickerView(s.instrument.symbol(), s.name, s.last,
                s.bids.isEmpty() ? 0 : s.bids.firstKey(), s.asks.isEmpty() ? 0 : s.asks.firstKey(),
                s.sessionOpen, s.volume, s.halted, s.instrument.priceScale(), s.instrument.tickSize(),
                s.instrument.lotSize());
    }

    public BookView book(String symbol, int levels) {
        synchronized (lock) {
            State s = bySymbol.get(symbol);
            return s == null ? null : book(s, levels);
        }
    }

    private BookView book(State s, int levels) {
        return new BookView(s.instrument.symbol(), appliedSeq, top(s.bids, levels), top(s.asks, levels));
    }

    private static List<LevelView> top(TreeMap<Long, Long> side, int levels) {
        List<LevelView> out = new ArrayList<>(Math.min(levels, side.size()));
        for (Map.Entry<Long, Long> e : side.entrySet()) {
            if (out.size() >= levels) break;
            out.add(new LevelView(e.getKey(), e.getValue()));
        }
        return out;
    }

    public List<TradeView> trades(String symbol, int limit) {
        synchronized (lock) {
            State s = bySymbol.get(symbol);
            if (s == null) return null;
            List<TradeView> out = new ArrayList<>(Math.min(limit, s.trades.size()));
            for (TradeView t : s.trades) {
                if (out.size() >= limit) break;
                out.add(t);
            }
            return out;
        }
    }

    public List<CandleView> candles(String symbol, int intervalSeconds, int limit) {
        int index = intervalIndex(intervalSeconds);
        synchronized (lock) {
            State s = bySymbol.get(symbol);
            if (s == null || index < 0) return null;
            List<CandleView> all = new ArrayList<>();
            for (Candle c : s.candles.get(index).values()) all.add(c.view());
            return all.size() <= limit ? all : new ArrayList<>(all.subList(all.size() - limit, all.size()));
        }
    }

    public Map<Integer, List<CandleView>> allCandles(String symbol, int limit) {
        Map<Integer, List<CandleView>> out = new LinkedHashMap<>();
        for (int interval : INTERVALS) out.put(interval, candles(symbol, interval, limit));
        return out;
    }

    public List<Update> drainUpdates(int levels) {
        synchronized (lock) {
            List<Update> out = new ArrayList<>();
            for (State s : bySymbol.values()) {
                if (!s.bookDirty && s.pendingTrades.isEmpty() && !s.candlesDirty) continue;
                BookView book = s.bookDirty ? book(s, levels) : null;
                List<TradeView> trades = s.pendingTrades.isEmpty() ? List.of() : new ArrayList<>(s.pendingTrades);
                Map<Integer, CandleView> candles = null;
                if (s.candlesDirty) {
                    candles = new LinkedHashMap<>();
                    for (int i = 0; i < INTERVALS.length; i++) {
                        var last = s.candles.get(i).lastEntry();
                        if (last != null) candles.put(INTERVALS[i], last.getValue().view());
                    }
                }
                s.pendingTrades.clear();
                s.bookDirty = false;
                s.candlesDirty = false;
                out.add(new Update(s.instrument.symbol(), book, trades, candles));
            }
            return out;
        }
    }

    public static int intervalIndex(int seconds) {
        for (int i = 0; i < INTERVALS.length; i++) if (INTERVALS[i] == seconds) return i;
        return -1;
    }
}
