package dev.glassbook.core.journal;

import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class CommandCodec {

    private static final byte PLACE = 1;
    private static final byte CANCEL = 2;
    private static final byte DEPOSIT = 3;
    private static final byte HALT = 4;

    private CommandCodec() {}

    public static void encode(Command command, ByteBuffer out) {
        switch (command) {
            case Command.PlaceOrder c -> {
                byte[] id = c.clientOrderId().getBytes(StandardCharsets.UTF_8);
                out.put(PLACE);
                out.putLong(c.participantId());
                out.putInt(c.instrumentId());
                out.put((byte) c.side().ordinal());
                out.put((byte) c.type().ordinal());
                out.put((byte) c.timeInForce().ordinal());
                out.putLong(c.price());
                out.putLong(c.quantity());
                out.putShort((short) id.length);
                out.put(id);
            }

            case Command.CancelOrder c -> {
                out.put(CANCEL);
                out.putLong(c.participantId());
                out.putLong(c.orderId());
            }

            case Command.Deposit c -> {
                out.put(DEPOSIT);
                out.putLong(c.accountId());
                out.putInt(c.assetId());
                out.putLong(c.amount());
            }

            case Command.SetHalted c -> {
                out.put(HALT);
                out.putInt(c.instrumentId());
                out.put((byte) (c.halted() ? 1 : 0));
            }
        }
    }

    public static Command decode(ByteBuffer in) {
        byte type = in.get();
        return switch (type) {
            case PLACE -> {
                long participant = in.getLong();
                int instrument = in.getInt();
                Side side = Side.values()[in.get()];
                OrderType orderType = OrderType.values()[in.get()];
                TimeInForce tif = TimeInForce.values()[in.get()];
                long price = in.getLong();
                long quantity = in.getLong();
                byte[] id = new byte[in.getShort()];
                in.get(id);
                yield new Command.PlaceOrder(participant, instrument, new String(id, StandardCharsets.UTF_8),
                        side, orderType, tif, price, quantity);
            }
            case CANCEL -> new Command.CancelOrder(in.getLong(), in.getLong());
            case DEPOSIT -> new Command.Deposit(in.getLong(), in.getInt(), in.getLong());
            case HALT -> new Command.SetHalted(in.getInt(), in.get() == 1);
            default -> throw new IllegalArgumentException("Неизвестный тип команды " + type);
        };
    }
}
