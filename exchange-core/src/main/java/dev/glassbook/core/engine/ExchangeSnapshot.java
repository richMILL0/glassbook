package dev.glassbook.core.engine;

import dev.glassbook.core.book.Order;
import dev.glassbook.core.ledger.Account;
import dev.glassbook.core.ledger.Ledger;
import dev.glassbook.core.model.Instrument;
import dev.glassbook.core.model.Side;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class ExchangeSnapshot {

    private static final int MAGIC = 0x47425350;
    private static final int VERSION = 1;

    private ExchangeSnapshot() {}

    public static void write(Exchange x, DataOutput out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(x.lastSeq());
        out.writeLong(x.nextOrderId());
        out.writeLong(x.nextTradeId());

        int n = x.instrumentCount();
        out.writeInt(n);
        for (int id = 1; id <= n; id++) {
            Instrument i = x.instrument(id);
            out.writeUTF(i.symbol());
            out.writeLong(i.tickSize());
            out.writeLong(i.lotSize());
            out.writeBoolean(x.isHalted(id));
        }

        Ledger ledger = x.ledger();
        long[] ids = ledger.accounts().keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        out.writeInt(ids.length);
        for (long accountId : ids) {
            Account a = ledger.account(accountId);
            out.writeLong(accountId);
            for (int asset = 0; asset < ledger.assetCount(); asset++) {
                out.writeLong(a.free(asset));
                out.writeLong(a.held(asset));
            }
        }

        for (int id = 1 ; id <= n ; ++id) {
            for (Side side : Side.values()) {
                out.writeInt(x.book(id).depth(side, Integer.MAX_VALUE).stream().mapToInt(l -> l.orders()).sum());
                x.book(id).forEachOrder(side, o -> {
                    try {
                        out.writeLong(o.id);
                        out.writeLong(o.participantId);
                        out.writeLong(o.price);
                        out.writeLong(o.quantity);
                        out.writeLong(o.remaining);
                        out.writeLong(o.seq);
                        out.writeLong(o.timestamp);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            }
        }

        Map<String, Long> idem = x.idempotency().entries();
        out.writeInt(idem.size());
        for (Map.Entry<String, Long> e : idem.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeLong(e.getValue());
        }
    }

    public static Exchange read(DataInput in, List<Instrument> config, int idempotencyCapacity) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("Это не снапшот");
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("Не поддерживаемая версия снапшота" + version);
        }

        Exchange x = new Exchange(config, idempotencyCapacity);
        long lastSeq = in.readLong();
        long nextOrderId = in.readLong();
        long nextTradeId = in.readLong();
        x.restoreCounters(lastSeq, nextOrderId, nextTradeId);

        int n = in.readInt();
        if (n != x.instrumentCount()) {
            throw new IOException("Отсутствует инструмент. Снапшот: " + n);
        }
        for (int id = 1 ; id <= n ; ++id) {
            Instrument i = x.instrument(id);
            String symbol = in.readUTF();
            long tick = in.readLong();
            long lot = in.readLong();
            boolean halted = in.readBoolean();

            if (!symbol.equals(i.symbol()) || tick != i.tickSize() || lot != i.lotSize()) {
                throw new IOException("Конфигурацию инструмента изменилась после последнего снапшота: " + symbol);
            }
            x.restoreHalted(id, halted);
        }

        Ledger ledger = x.ledger();
        int accounts = in.readInt();
        for (int k = 0; k < accounts; k++) {
            long accountId = in.readLong();
            Account a = ledger.restoreAccount(accountId);
            for (int asset = 0 ; asset < ledger.assetCount() ; ++asset) {
                long free = in.readLong();
                long held = in.readLong();
                ledger.restoreBalance(a, asset, free, held);
            }
        }

        for (int id = 1; id <= n; id++) {
            for (Side side : Side.values()) {
                int count = in.readInt();
                for (int k = 0; k < count; k++) {
                    long orderId = in.readLong();
                    long participantId = in.readLong();
                    long price = in.readLong();
                    long quantity = in.readLong();
                    long remaining = in.readLong();
                    long seq = in.readLong();
                    long ts = in.readLong();
                    x.restoreOrder(new Order(orderId, participantId, id, side, price, quantity, remaining, seq, ts));
                }
            }
        }

        int idemCount = in.readInt();
        for (int k = 0 ; k < idemCount ; ++k) {
            String key = in.readUTF();
            long orderId = in.readLong();
            x.idempotency().put(key, orderId);
        }
        return x;
    }

    public static byte[] toBytes(Exchange x) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            write(x, out);
        }
        return bytes.toByteArray();
    }

    public static String fingerprint(Exchange x) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), md))) {
                write(x, out);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Exchange fromBytes(byte[] bytes, List<Instrument> config, int idempotencyCapacity) throws IOException {
        return read(new DataInputStream(new java.io.ByteArrayInputStream(bytes)), config, idempotencyCapacity);
    }
}
