package dev.glassbook.server.config;

import dev.glassbook.core.engine.EngineConfig;
import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.server.pipeline.EventPipeline;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

@Configuration
public class EngineConfiguration {

    @Bean(destroyMethod = "close")
    public ExchangeCore exchangeCore(InstrumentCatalog catalog, GlassbookProperties properties,
                                     EventPipeline pipeline) throws IOException {
        GlassbookProperties.Engine e = properties.engine();
        EngineConfig base = EngineConfig.defaults(Path.of(e.dataDir()));
        EngineConfig config = new EngineConfig(base.dataDir(), e.queueCapacity(), e.maxBatch(), e.fsync(),
                e.snapshotEveryCommands(), base.segmentMaxBytes(), base.idempotencyCapacity(),
                base.snapshotsToKeep(), true, base.clock());
        ExchangeCore core = new ExchangeCore(catalog.instruments(), config, pipeline);
        core.start();
        return core;
    }
}
