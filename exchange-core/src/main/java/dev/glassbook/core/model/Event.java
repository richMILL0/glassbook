package dev.glassbook.core.model;

public sealed interface Event {

    long seq();         /// Номер команды, которая вызвала

    long timestamp();   /// Время этой команды

    record OrderAccepted(long seq, long timestamp, long orderId, long participantId, int instrumentId,
                         String clientOrderId, Side side, OrderType type, TimeInForce timeInForce,
                         long price, long quantity) implements Event {}

    record OrderRejected(long seq, long timestamp, long participantId, int instrumentId, String clientOrderId,
                         RejectReason reason, long existingOrderId) implements Event {}

    record CommandRejected(long seq, long timestamp, long accountId, RejectReason reason, long refId)
            implements Event {}

    record TradeExecuted(long seq, long timestamp, long tradeId, int instrumentId, long price, long quantity,
                         long buyOrderId, long sellOrderId, long buyerId, long sellerId, Side takerSide)
            implements Event {}

    record OrderCancelled(long seq, long timestamp, long orderId, long participantId, int instrumentId,
                          long remainingQuantity, CancelReason reason) implements Event {}

    record BookLevelChanged(long seq, long timestamp, int instrumentId, Side side, long price, long totalQuantity)
            implements Event {}

    record Posted(long seq, long timestamp, long accountId, int assetId, long delta, PostingKind kind, long refId)
            implements Event {}

    record TradingStatusChanged(long seq, long timestamp, int instrumentId, boolean halted) implements Event {}
}
