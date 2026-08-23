package dev.glassbook.core.ledger;

import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.EventSink;
import dev.glassbook.core.model.PostingKind;
import dev.glassbook.core.model.RejectReason;

import java.util.HashMap;
import java.util.Map;

public final class Ledger {

    public static final long SYSTEM = 0L;
    public static final int CASH = 0;

    public static final long MAX_DEPOSIT = 1_000_000_000_000_000L;
    public static final long MAX_BALANCE = Long.MAX_VALUE / 2;

    private final int assetCount;
    private final Map<Long, Account> accounts = new HashMap<>();

    public Ledger(int assetCount) {
        this.assetCount = assetCount;
        accounts.put(SYSTEM, new Account(SYSTEM, assetCount));
    }

    public int assetCount() {
        return assetCount;
    }

    public Account account(long id) {
        return accounts.get(id);
    }

    public Map<Long, Account> accounts() {
        return accounts;
    }

    public Account restoreAccount(long id) {
        return accounts.computeIfAbsent(id, k -> new Account(k, assetCount));
    }

    public void restoreBalance(Account a, int asset, long free, long held) {
        a.addFree(asset, free - a.free(asset));
        a.addHeld(asset, held - a.held(asset));
    }

    // ---------------------------------------------------------------- Депозит

    public RejectReason deposit(long seq, long ts, long accountId, int asset, long amount, EventSink sink) {
        if (accountId == SYSTEM || asset < 0 || asset >= assetCount || amount < 1 || amount > MAX_DEPOSIT) {
            return RejectReason.INVALID_AMOUNT;
        }

        Account target = accounts.get(accountId);
        long current = target == null ? 0 : target.total(asset);
        if (current > MAX_BALANCE - amount) {
            return RejectReason.BALANCE_OVERFLOW;
        }

        Account system = accounts.get(SYSTEM);
        if (system.free(asset) < -MAX_BALANCE + amount) {
            return RejectReason.BALANCE_OVERFLOW;
        }

        if (target == null) {
            target = new Account(accountId, assetCount);
            accounts.put(accountId, target);
        }

        system.addFree(asset, -amount);
        target.addFree(asset, amount);
        sink.accept(new Event.Posted(seq, ts, SYSTEM, asset, -amount, PostingKind.DEPOSIT, accountId));
        sink.accept(new Event.Posted(seq, ts, accountId, asset, amount, PostingKind.DEPOSIT, accountId));
        return null;
    }

    // ---------------------------------------------------------------- Резервы

    public boolean hold(Account a, int asset, long amount) {
        if (amount < 0 || a.free(asset) < amount) {
            return false;
        }

        a.addFree(asset, -amount);
        a.addHeld(asset, amount);
        return true;
    }

    public void release(Account a, int asset, long amount) {
        if (amount < 0 || a.held(asset) < amount) {
            throw new IllegalStateException("Попытка освободить больше, чем доступно в held");
        }

        a.addHeld(asset, -amount);
        a.addFree(asset, amount);
    }

    // ---------------------------------------------------------------- trade settlement

    public void settleTrade(long seq, long ts, long tradeId, int instrumentId, long buyerId, long sellerId,
                            long price, long quantity, long buyerReservedPrice, EventSink sink) {
        Account buyer = accounts.get(buyerId);
        Account seller = accounts.get(sellerId);

        long cost = price * quantity;
        long reserved = buyerReservedPrice * quantity;

        buyer.addHeld(CASH, -reserved);
        buyer.addFree(CASH, reserved - cost);
        buyer.addFree(instrumentId, quantity);

        seller.addHeld(instrumentId, -quantity);
        seller.addFree(CASH, cost);

        sink.accept(new Event.Posted(seq, ts, buyerId, CASH, -cost, PostingKind.TRADE, tradeId));
        sink.accept(new Event.Posted(seq, ts, sellerId, CASH, cost, PostingKind.TRADE, tradeId));
        sink.accept(new Event.Posted(seq, ts, sellerId, instrumentId, -quantity, PostingKind.TRADE, tradeId));
        sink.accept(new Event.Posted(seq, ts, buyerId, instrumentId, quantity, PostingKind.TRADE, tradeId));
    }
}
