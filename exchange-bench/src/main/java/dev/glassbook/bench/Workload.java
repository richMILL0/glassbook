package dev.glassbook.bench;

import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.Instrument;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;

import java.util.List;
import java.util.SplittableRandom;


public final class Workload {

    public static final Instrument INSTRUMENT = new Instrument(1, "BNCH", 1, 1, 2);
    public static final List<Instrument> INSTRUMENTS = List.of(INSTRUMENT); /// Список инструментов
    public static final int PARTICIPANTS = 1_000;                           /// Количество участников
    public static final long CASH = 1_000_000_000_000L;                     /// Стартовый капитал
    public static final long SHARES = 1_000_000L;                           /// Стартовые акции

    private final SplittableRandom rnd;                                     /// Генератор случайных чисел(чтобы была разная последовательность команд)
    private final long[] liveIds = new long[4096];                          /// Список заявок, которые можно отменить
    private int liveCount;                                                  /// Сколько ячеек в liveIds занято
    private long mid = 100_000;                                             /// Средняя цена рынка
    public long accepted;                                                   /// Сколько заявок принял движок
    public long trades;                                                     /// Сколько совершено сделок

    public Workload(long seed) {
        this.rnd = new SplittableRandom(seed);
    }

    public static Command[] funding() {
        Command[] out = new Command[PARTICIPANTS * 2];
        for (int i = 0; i < PARTICIPANTS; i++) {
            out[2 * i] = new Command.Deposit(i + 1, 0, CASH);
            out[2 * i + 1] = new Command.Deposit(i + 1, 1, SHARES);
        }
        return out;
    }

    public void observe(Event e) {
        if (e instanceof Event.OrderAccepted a) {
            accepted++;

            if (liveCount < liveIds.length) {
                liveIds[liveCount++] = a.orderId();
            } else {
                liveIds[rnd.nextInt(liveIds.length)] = a.orderId();
            }

        } else if (e instanceof Event.TradeExecuted) {
            trades++;
        }
    }

    public Command next() {
        mid = Math.max(1_000, mid + rnd.nextInt(-2, 3));
        long participant = rnd.nextInt(PARTICIPANTS) + 1;
        int roll = rnd.nextInt(100);

        Side side = rnd.nextBoolean() ? Side.BUY : Side.SELL;
        if (roll < 15 && liveCount > 0) {
            int i = rnd.nextInt(liveCount);
            long id = liveIds[i];
            liveIds[i] = liveIds[--liveCount];
            return new Command.CancelOrder(participant, id);
        }

        long qty = rnd.nextInt(1, 100);
        if (roll < 20) {
            return new Command.PlaceOrder(participant, 1, "", side, OrderType.MARKET, TimeInForce.IOC, 0, qty);
        }

        long offset = rnd.nextInt(-3, 40);
        /// От -3 до 40, чтобы были пассивные и агрессивные заявки.
        /// Если выпадет [-3, -1], то сделка состоится
        long price = side == Side.BUY ? mid - offset : mid + offset;
        return new Command.PlaceOrder(participant, 1, "", side, OrderType.LIMIT, TimeInForce.GTC,
                Math.max(1, price), qty);
    }
}
