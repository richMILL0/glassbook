package dev.glassbook.server.api;

import dev.glassbook.server.market.MarketDataService;
import dev.glassbook.server.market.Views.BookView;
import dev.glassbook.server.market.Views.CandleView;
import dev.glassbook.server.market.Views.TickerView;
import dev.glassbook.server.market.Views.TradeView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@Validated
public class MarketController {

    private final MarketDataService marketData;

    public MarketController(MarketDataService marketData) {
        this.marketData = marketData;
    }

    @GetMapping("/instruments")
    public List<TickerView> instruments() {
        return marketData.tickers();
    }

    @GetMapping("/market/{symbol}/book")
    public BookView book(@PathVariable String symbol, @RequestParam(defaultValue = "15") @Min(1) @Max(100) int levels) {
        return found(marketData.book(symbol.toUpperCase(), levels));
    }

    @GetMapping("/market/{symbol}/trades")
    public List<TradeView> trades(@PathVariable String symbol,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(300) int limit) {
        return found(marketData.trades(symbol.toUpperCase(), limit));
    }

    @GetMapping("/market/{symbol}/candles")
    public List<CandleView> candles(@PathVariable String symbol, @RequestParam(defaultValue = "5") int interval,
                                    @RequestParam(defaultValue = "200") @Min(1) @Max(600) int limit) {
        return found(marketData.candles(symbol.toUpperCase(), interval, limit));
    }

    private static <T> T found(T value) {
        if (value == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "unknown symbol or interval");
        }

        return value;
    }
}
