package dev.glassbook.core;

import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

public final class ScenarioGenerator {

    public enum Mode {
        POOR,
        RICH_VALID
    }

    public static final int PARTICIPANTS = 6;

    private final SplittableRandom rnd;
    private final Mode mode;

    public ScenarioGenerator(long seed, Mode mode) {
        this.rnd = new SplittableRandom(seed);
        this.mode = mode;
    }

    public List<Command> script(int steps) {
        List<Command> out = new ArrayList<>(steps + 64);
        for (long p = 1; p <= PARTICIPANTS; p++) {
            if (mode == Mode.RICH_VALID) {
                out.add(new Command.Deposit(p, 0, 10_000_000_000_000L));
                out.add(new Command.Deposit(p, 1, 100_000_000L));
                out.add(new Command.Deposit(p, 2, 100_000_000L));
            } else {
                out.add(new Command.Deposit(p, 0, 5_000 + rnd.nextLong(300_000)));
                out.add(new Command.Deposit(p, 1, 20 + rnd.nextLong(300)));
                out.add(new Command.Deposit(p, 2, 100 + rnd.nextLong(900)));
            }
        }
        long placed = 0;
        for (int i = 0; i < steps; i++) {
            long participant = 1 + rnd.nextInt(PARTICIPANTS);
            int instrument = rnd.nextInt(10) < 7 ? 1 : 2;
            int kind = rnd.nextInt(100);
            if (kind < 50) {
                out.add(order(participant, instrument, OrderType.LIMIT, TimeInForce.GTC));
                placed++;
            } else if (kind < 62) {
                out.add(order(participant, instrument, OrderType.MARKET, TimeInForce.IOC));
                placed++;
            } else if (kind < 70) {
                out.add(order(participant, instrument, OrderType.LIMIT, TimeInForce.IOC));
                placed++;
            } else if (kind < 76) {
                out.add(order(participant, instrument, OrderType.LIMIT, TimeInForce.FOK));
                placed++;
            } else if (kind < 82) {
                out.add(order(participant, instrument, OrderType.LIMIT, TimeInForce.POST_ONLY));
                placed++;
            } else if (kind < 94 || mode == Mode.RICH_VALID) {
                // order ids are sequential per accepted order, so this hits live orders often and misses sometimes
                long target = 1 + rnd.nextLong(Math.max(1, placed + 1));
                long owner = rnd.nextInt(8) == 0 ? 1 + rnd.nextInt(PARTICIPANTS) : participant;
                out.add(new Command.CancelOrder(owner, target));
            } else if (kind < 97) {
                int asset = rnd.nextInt(3);
                out.add(new Command.Deposit(participant, asset, 1 + rnd.nextLong(asset == 0 ? 100_000 : 200)));
            } else if (kind < 98) {
                out.add(new Command.SetHalted(instrument, rnd.nextBoolean()));
            } else {
                out.add(invalid(participant, instrument));
            }
        }
        return out;
    }

    private Command.PlaceOrder order(long participant, int instrument, OrderType type, TimeInForce tif) {
        Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
        long mid = instrument == 1 ? 1_000 : 5_000;
        long step = instrument == 1 ? 1 : 5;
        long price = type == OrderType.MARKET ? 0 : mid + step * (rnd.nextLong(31) - 15);
        long qty = instrument == 1 ? 1 + rnd.nextLong(40) : 10 * (1 + rnd.nextLong(10));
        String clientId = mode == Mode.POOR && rnd.nextInt(5) == 0 ? "c" + rnd.nextInt(40) : "";
        return new Command.PlaceOrder(participant, instrument, clientId, side, type, tif, price, qty);
    }

    private Command invalid(long participant, int instrument) {
        return switch (rnd.nextInt(6)) {
            case 0 -> new Command.PlaceOrder(participant, instrument, "", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 1_000, 0);
            case 1 -> new Command.PlaceOrder(participant, instrument, "", Side.SELL, OrderType.LIMIT, TimeInForce.GTC, 1_000, -5);
            case 2 -> new Command.PlaceOrder(participant, 2, "", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 5_003, 10); // off tick
            case 3 -> new Command.PlaceOrder(participant, 2, "", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 5_000, 7);  // off lot
            case 4 -> new Command.PlaceOrder(participant, 9, "", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 1_000, 1);  // no such instrument
            default -> new Command.PlaceOrder(77, instrument, "", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 1_000, 1); // no such account
        };
    }
}
