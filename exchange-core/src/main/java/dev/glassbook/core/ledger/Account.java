package dev.glassbook.core.ledger;

import java.util.Arrays;

public final class Account {
    private final long id;
    private long[] free;
    private long[] held;

    Account(long id, int assetCount) {
        this.id = id;
        this.free = new long[assetCount];
        this.held = new long[assetCount];
    }

    public long id() {
        return id;
    }

    public int assetCount() {
        return free.length;
    }

    public long free(int asset) {
        return asset < free.length ? free[asset] : 0;
    }

    public long held(int asset) {
        return asset < held.length ? held[asset] : 0;
    }

    public long total(int asset) {
        return free(asset) + held(asset);
    }

    void ensureAssets(int assetCount) {
        if (assetCount > free.length) {
            free = Arrays.copyOf(free, assetCount);
            held = Arrays.copyOf(held, assetCount);
        }
    }

    void addFree(int asset, long delta) {
        free[asset] += delta;
    }

    void addHeld(int asset, long delta) {
        held[asset] += delta;
    }
}
