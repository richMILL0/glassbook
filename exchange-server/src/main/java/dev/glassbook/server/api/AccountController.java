package dev.glassbook.server.api;

import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.core.ledger.Account;
import dev.glassbook.server.config.InstrumentCatalog;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    public record PositionView(String symbol, long free, long held) { }

    public record AccountView(long participantId, long cashFree, long cashHeld, List<PositionView> positions) { }

    private final ExchangeCore core;
    private final InstrumentCatalog catalog;

    public AccountController(ExchangeCore core, InstrumentCatalog catalog) {
        this.core = core;
        this.catalog = catalog;
    }

    @GetMapping
    public CompletableFuture<AccountView> balances(@AuthenticationPrincipal Jwt jwt) {
        long participant = Long.parseLong(jwt.getSubject());
        return core.query(x -> {
            Account a = x.ledger().account(participant);

            List<PositionView> positions = new ArrayList<>();
            for (InstrumentCatalog.Entry e : catalog.entries()) {
                int asset = e.instrument().id();
                positions.add(new PositionView(e.instrument().symbol(),
                        a == null ? 0 : a.free(asset), a == null ? 0 : a.held(asset)));
            }

            return new AccountView(participant, a == null ? 0 : a.free(0), a == null ? 0 : a.held(0), positions);
        });
    }
}
