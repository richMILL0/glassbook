package dev.glassbook.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "glassbook")
public record GlassbookProperties(
        @DefaultValue Engine engine,
        @DefaultValue Security security,
        @DefaultValue Demo demo,
        @DefaultValue Simulator simulator,
        @DefaultValue Kafka kafka,
        List<InstrumentConfig> instruments) {

    public record Engine(
            @DefaultValue("./data/engine") String dataDir,
            @DefaultValue("65536") int queueCapacity,
            @DefaultValue("512") int maxBatch,
            @DefaultValue("true") boolean fsync,
            @DefaultValue("200000") long snapshotEveryCommands) {}

    public record Security(
            @DefaultValue("dev-only-secret-change-me-dev-only-secret-change-me") String jwtSecret,
            @DefaultValue("PT12H") Duration tokenTtl,
            @DefaultValue("operator") String adminUsername,
            @DefaultValue("operator-change-me") String adminPassword,
            @DefaultValue("1200") int orderRatePerMinute) {}

    public record Demo(
            @DefaultValue("1000000000") long welcomeCash,
            @DefaultValue("1000") long welcomeShares) {}

    public record Simulator(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("12") int bots,
            @DefaultValue("250") int operationsPerSecond,
            @DefaultValue("42") long seed) {}

    public record Kafka(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("glassbook.trades") String tradesTopic) {}

    public record InstrumentConfig(int id, String symbol, String name, long tickSize, long lotSize, int priceScale,
                                   long initialPrice) {}
}
