package dev.glassbook.server.audit;

import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.server.config.InstrumentCatalog;
import dev.glassbook.server.pipeline.EventPipeline;
import dev.glassbook.server.projection.LedgerEntryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class AuditService {

    public record DatabaseSum(String asset, long net) { }

    public record AuditReport(boolean ok, boolean databaseInSync, long engineSeq, long projectedSeq,
                              LedgerAudit.EngineAudit engine, List<DatabaseSum> databaseLedgerSums) { }

    private final ExchangeCore core;
    private final InstrumentCatalog catalog;
    private final LedgerEntryRepository ledgerEntries;
    private final EventPipeline pipeline;

    public AuditService(ExchangeCore core, InstrumentCatalog catalog, LedgerEntryRepository ledgerEntries,
                        EventPipeline pipeline) {
        this.core = core;
        this.catalog = catalog;
        this.ledgerEntries = ledgerEntries;
        this.pipeline = pipeline;
    }

    public CompletableFuture<AuditReport> run() {
        return core.query(x -> LedgerAudit.compute(x, catalog)).thenApply(engine -> {
            long projected = pipeline.projectedSeq();
            boolean inSync = projected >= engine.seq();
            List<DatabaseSum> sums = new ArrayList<>();
            boolean dbBalanced = true;

            for (LedgerEntryRepository.AssetSum s : ledgerEntries.sumByAsset()) {
                String name = s.getAssetId() == 0 ? "CASH" : catalog.byId(s.getAssetId()).instrument().symbol();
                long net = s.getTotal() == null ? 0 : s.getTotal();
                sums.add(new DatabaseSum(name, net));

                if (inSync && net != 0) {
                    dbBalanced = false;
                }
            }
            return new AuditReport(engine.ok() && dbBalanced, inSync, engine.seq(), projected, engine, sums);
        });
    }
}
