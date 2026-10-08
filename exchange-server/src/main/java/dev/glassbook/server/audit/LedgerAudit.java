package dev.glassbook.server.audit;

import dev.glassbook.core.engine.Exchange;
import dev.glassbook.core.ledger.Account;
import dev.glassbook.core.ledger.Ledger;
import dev.glassbook.core.model.Side;
import dev.glassbook.server.config.InstrumentCatalog;

import java.util.ArrayList;
import java.util.List;

public final class LedgerAudit {

    public record AssetAudit(String asset, long issued, long participantsTotal, long netOfAllAccounts,
                             long reservedByAccounts, long requiredByOpenOrders, boolean negativeBalances,
                             boolean ok) { }

    public record EngineAudit(long seq, long accounts, long openOrders, boolean ok, List<AssetAudit> assets) { }

    private LedgerAudit() { }

    public static EngineAudit compute(Exchange x, InstrumentCatalog catalog) {
        Ledger ledger = x.ledger();
        int assets = ledger.assetCount();
        long[] required = new long[assets];
        for (int id = 1; id <= x.instrumentCount(); id++) {
            final int asset = id;
            x.book(id).forEachOrder(Side.BUY, o -> required[Ledger.CASH] += o.price * o.remaining);
            x.book(id).forEachOrder(Side.SELL, o -> required[asset] += o.remaining);
        }

        List<AssetAudit> out = new ArrayList<>();
        boolean allOk = true;
        for (int asset = 0 ; asset < assets ; ++asset) {
            long net = 0;
            long participants = 0;
            long held = 0;
            boolean negative = false;

            for (Account a : ledger.accounts().values()) {
                net += a.total(asset);
                if (a.id() == Ledger.SYSTEM) {
                    continue;
                }

                participants += a.total(asset);
                held += a.held(asset);
                if (a.free(asset) < 0 || a.held(asset) < 0) {
                    negative = true;
                }
            }

            long issued = -ledger.account(Ledger.SYSTEM).total(asset);
            boolean ok = net == 0 && participants == issued && held == required[asset] && !negative;
            allOk &= ok;
            String name = asset == 0 ? "CASH" : catalog.byId(asset).instrument().symbol();
            out.add(new AssetAudit(name, issued, participants, net, held, required[asset], negative, ok));
        }
        return new EngineAudit(x.lastSeq(), ledger.accounts().size() - 1L, x.restingOrderCount(), allOk, out);
    }
}
