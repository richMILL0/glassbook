package dev.glassbook.server.api;

import dev.glassbook.core.engine.CommandResult;
import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.core.model.Command;
import dev.glassbook.server.audit.AuditService;
import dev.glassbook.server.config.InstrumentCatalog;
import dev.glassbook.server.pipeline.EventPipeline;
import dev.glassbook.server.projection.OutboxRepository;
import dev.glassbook.server.user.UserEntity;
import dev.glassbook.server.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('OPERATOR')")
public class AdminController {

    public record DepositRequest(@NotBlank String username, @NotBlank String asset, @Min(1) long amount) { }

    public record EngineStatus(String state, long seq, int queueDepth, long commands, long batches,
                               double avgFsyncMicros, double avgApplyMicros, long snapshots, long lastSnapshotSeq,
                               long projectedSeq, long projectionLag, int pipelineBacklog, long pendingOutbox) { }

    private final ExchangeCore core;
    private final InstrumentCatalog catalog;
    private final UserRepository users;
    private final AuditService audit;
    private final EventPipeline pipeline;
    private final OutboxRepository outbox;

    public AdminController(ExchangeCore core, InstrumentCatalog catalog, UserRepository users, AuditService audit,
                           EventPipeline pipeline, OutboxRepository outbox) {
        this.core = core;
        this.catalog = catalog;
        this.users = users;
        this.audit = audit;
        this.pipeline = pipeline;
        this.outbox = outbox;
    }

    @PostMapping("/deposit")
    public CompletableFuture<CommandResult> deposit(@Valid @RequestBody DepositRequest request) {
        UserEntity user = users.findByUsername(request.username())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_USER", "Пользователь не найден"));

        int asset;
        if (request.asset().equalsIgnoreCase("CASH")) {
            asset = 0;
        } else {
            InstrumentCatalog.Entry entry = catalog.bySymbol(request.asset());
            if (entry == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_ASSET", "Объект не найден");
            }
            asset = entry.instrument().id();
        }

        return core.submit(new Command.Deposit(user.getId(), asset, request.amount()));
    }

    @PostMapping("/instruments/{symbol}/halt")
    public CompletableFuture<CommandResult> halt(@PathVariable String symbol, @RequestParam boolean halted) {
        InstrumentCatalog.Entry entry = catalog.bySymbol(symbol);
        if (entry == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_INSTRUMENT", "Инструмент не найден");
        }

        return core.submit(new Command.SetHalted(entry.instrument().id(), halted));
    }

    @PostMapping("/snapshot")
    public CompletableFuture<Long> snapshot() {
        return core.snapshot();
    }

    @GetMapping("/engine")
    public EngineStatus engine() {
        ExchangeCore.Stats s = core.stats();
        long projected = pipeline.projectedSeq();

        return new EngineStatus(s.state().name(), s.lastSeq(), s.queueDepth(), s.commands(), s.batches(),
                s.batches() == 0 ? 0 : s.fsyncNanos() / 1_000.0 / s.batches(),
                s.commands() == 0 ? 0 : s.applyNanos() / 1_000.0 / s.commands(),
                s.snapshots(), s.lastSnapshotSeq(), projected, s.lastSeq() - projected, pipeline.backlog(),
                outbox.countByPublishedAtIsNull());
    }

    /// Запускает аудит
    @GetMapping("/audit")
    public CompletableFuture<AuditService.AuditReport> audit() {
        return audit.run();
    }
}
