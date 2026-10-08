package dev.glassbook.server.sim;

import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Instrument;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;
import dev.glassbook.server.config.GlassbookProperties;
import dev.glassbook.server.config.InstrumentCatalog;
import dev.glassbook.server.user.Role;
import dev.glassbook.server.user.UserEntity;
import dev.glassbook.server.user.UserRepository;
import dev.glassbook.server.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Order(10)
@ConditionalOnProperty(prefix = "glassbook.simulator", name = "enabled", havingValue = "true")
public class MarketSimulator implements ApplicationRunner, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(MarketSimulator.class);
    private static final int TICK_MILLIS = 20;
    private static final int MAX_LIVE_QUOTES = 6;

    private enum Kind {
        MAKER,
        NOISE,
        WHALE
    }

    private static final class Bot {
        final long id;
        final Kind kind;
        final List<ConcurrentLinkedDeque<Long>> liveOrders = new ArrayList<>();

        Bot(long id, Kind kind, int instruments) {
            this.id = id;
            this.kind = kind;
            for (int i = 0 ; i <= instruments ; ++i) {
                liveOrders.add(new ConcurrentLinkedDeque<>());
            }
        }
    }

    private final ExchangeCore core;
    private final InstrumentCatalog catalog;
    private final UserRepository users;
    private final UserService userService;
    private final GlassbookProperties.Simulator config;

    private final List<Bot> makers = new ArrayList<>();
    private final List<Bot> noise = new ArrayList<>();
    private Bot whale;
    private double[] fair;
    private SplittableRandom random;
    private ScheduledExecutorService executor;
    private double carry;
    private long nextWhaleAt;

    public MarketSimulator(ExchangeCore core, InstrumentCatalog catalog, UserRepository users,
                           UserService userService, GlassbookProperties properties) {
        this.core = core;
        this.catalog = catalog;
        this.users = users;
        this.userService = userService;
        this.config = properties.simulator();
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int instruments = catalog.entries().size();
        int total = Math.max(4, config.bots());

        for (int i = 1 ; i <= total ; ++i) {
            String name = "bot-" + i;
            UserEntity user = users.findByUsername(name)
                    .orElseGet(() -> userService.create(name, UUID.randomUUID().toString(), Role.BOT));
            boolean funded = core.query(x -> x.ledger().account(user.getId()) != null).get(10, TimeUnit.SECONDS);

            if (!funded) {
                userService.grant(user.getId(), 1_000_000_000_000L, 200_000L).get(30, TimeUnit.SECONDS);
            }

            Kind kind = i == total ? Kind.WHALE : i <= total * 2 / 5 ? Kind.MAKER : Kind.NOISE;
            Bot bot = new Bot(user.getId(), kind, instruments);

            switch (kind) {
                case MAKER -> makers.add(bot);
                case NOISE -> noise.add(bot);
                case WHALE -> whale = bot;
            }
        }
        random = new SplittableRandom(config.seed());
        fair = new double[instruments + 1];

        for (var e : catalog.entries()) {
            fair[e.instrument().id()] = e.initialPrice();
        }
        nextWhaleAt = System.currentTimeMillis() + 30_000;

        executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "market-simulator"));
        executor.scheduleAtFixedRate(this::tick, 500, TICK_MILLIS, TimeUnit.MILLISECONDS);
        log.info("market simulator started: {} makers, {} noise traders, 1 whale, ~{} ops/s",
                makers.size(), noise.size(), config.operationsPerSecond());
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ one scheduler tick

    private void tick() {
        try {
            if (core.stats().queueDepth() > 2_000) {
                return;
            }

            for (var e : catalog.entries()) {
                driftFairPrice(e);
            }

            carry += config.operationsPerSecond() * (TICK_MILLIS / 1000.0);
            int operations = (int) carry;
            carry -= operations;

            for (int i = 0 ; i < operations ; ++i) {
                var entry = catalog.entries().get(random.nextInt(catalog.entries().size()));
                int roll = random.nextInt(100);

                if (roll < 55) {
                    quote(makers.get(random.nextInt(makers.size())), entry);
                } else {
                    aggress(noise.get(random.nextInt(noise.size())), entry);
                }
            }

            if (System.currentTimeMillis() >= nextWhaleAt) {
                sweep(catalog.entries().get(random.nextInt(catalog.entries().size())));
                nextWhaleAt = System.currentTimeMillis() + 40_000 + random.nextInt(50_000);
            }
        } catch (RuntimeException e) {
            log.warn("simulator tick failed", e);
        }
    }

    private void driftFairPrice(InstrumentCatalog.Entry e) {
        int id = e.instrument().id();
        double shock = 0.0004 * gaussian();
        fair[id] *= Math.exp(shock);
        fair[id] += 0.0008 * (e.initialPrice() - fair[id]);
        fair[id] = Math.max(fair[id], e.instrument().tickSize() * 20.0);
    }

    private void quote(Bot maker, InstrumentCatalog.Entry e) {
        Instrument in = e.instrument();
        var live = maker.liveOrders.get(in.id());

        if (live.size() >= MAX_LIVE_QUOTES) {
            Long oldest = live.pollFirst();
            if (oldest != null) {
                core.submit(new Command.CancelOrder(maker.id, oldest));
            }
        }

        double halfSpread = Math.max(in.tickSize(), fair[in.id()] * 0.0004);
        double depth = random.nextInt(10) * halfSpread * 0.6;
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        double raw = side == Side.BUY ? fair[in.id()] - halfSpread - depth : fair[in.id()] + halfSpread + depth;
        long price = roundToTick(raw, in);
        long quantity = in.lotSize() * (5 + random.nextInt(40));
        core.submit(new Command.PlaceOrder(maker.id, in.id(), "", side, OrderType.LIMIT, TimeInForce.GTC, price,
                quantity)).thenAccept(result -> result.acceptedOrderId().ifPresent(live::addLast));
    }

    private void aggress(Bot trader, InstrumentCatalog.Entry e) {
        Instrument in = e.instrument();
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long quantity = in.lotSize() * (1 + random.nextInt(12));

        if (random.nextInt(100) < 55) {
            core.submit(new Command.PlaceOrder(trader.id, in.id(), "", side, OrderType.MARKET, TimeInForce.IOC, 0,
                    quantity));
        } else {
            double edge = fair[in.id()] * 0.0007 * random.nextInt(3);
            double raw = side == Side.BUY ? fair[in.id()] + edge : fair[in.id()] - edge;
            core.submit(new Command.PlaceOrder(trader.id, in.id(), "", side, OrderType.LIMIT, TimeInForce.IOC,
                    roundToTick(raw, in), quantity));
        }
    }

    private void sweep(InstrumentCatalog.Entry e) {
        Instrument in = e.instrument();
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long quantity = in.lotSize() * (250 + random.nextInt(250));
        log.info("whale {} {} x{}", side, in.symbol(), quantity);
        core.submit(new Command.PlaceOrder(whale.id, in.id(), "", side, OrderType.MARKET, TimeInForce.IOC, 0, quantity));
    }

    private static long roundToTick(double price, Instrument in) {
        long ticks = Math.max(1, Math.round(price / in.tickSize()));
        return ticks * in.tickSize();
    }

    private double gaussian() {
        double u1 = Math.max(1e-12, random.nextDouble());
        double u2 = random.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
}
