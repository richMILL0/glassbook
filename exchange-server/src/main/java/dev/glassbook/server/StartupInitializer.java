package dev.glassbook.server;

import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.server.config.GlassbookProperties;
import dev.glassbook.server.config.InstrumentCatalog;
import dev.glassbook.server.market.MarketDataService;
import dev.glassbook.server.market.MarketSnapshot;
import dev.glassbook.server.projection.TradeRecord;
import dev.glassbook.server.projection.TradeRepository;
import dev.glassbook.server.user.Role;
import dev.glassbook.server.user.UserRepository;
import dev.glassbook.server.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Order(0)
public class StartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final ExchangeCore core;
    private final MarketDataService marketData;
    private final TradeRepository trades;
    private final InstrumentCatalog catalog;
    private final UserRepository users;
    private final UserService userService;
    private final GlassbookProperties properties;

    public StartupInitializer(ExchangeCore core, MarketDataService marketData, TradeRepository trades,
                              InstrumentCatalog catalog, UserRepository users, UserService userService,
                              GlassbookProperties properties) {
        this.core = core;
        this.marketData = marketData;
        this.trades = trades;
        this.catalog = catalog;
        this.users = users;
        this.userService = userService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        MarketSnapshot snapshot = core.query(MarketSnapshot::capture).get(30, TimeUnit.SECONDS);
        Map<Integer, List<TradeRecord>> recent = new HashMap<>();
        for (var entry : catalog.entries()) {
            int id = entry.instrument().id();
            List<TradeRecord> newestFirst = new ArrayList<>(
                    trades.findByInstrumentIdOrderByIdDesc(id, PageRequest.of(0, 5_000)));
            Collections.reverse(newestFirst);
            recent.put(id, newestFirst);
        }
        marketData.initialize(snapshot, recent);
        log.info("market data initialized at engine seq {}", snapshot.seq());

        String operator = properties.security().adminUsername();
        if (!users.existsByUsername(operator)) {
            userService.create(operator, properties.security().adminPassword(), Role.OPERATOR);
            log.info("created operator login '{}'", operator);
        }
    }
}
