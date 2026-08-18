package dev.glassbook.core.model;

public sealed interface Command {

    record PlaceOrder(long participantId, int instrumentId, String clientOrderId, Side side, OrderType type,
                      TimeInForce timeInForce, long price, long quantity) implements Command {
        public PlaceOrder {
            if (clientOrderId == null) {
                clientOrderId = "";
            }
        }
    }

    record CancelOrder(long participantId, long orderId) implements Command {}

    record Deposit(long accountId, int assetId, long amount) implements Command {}

    record SetHalted(int instrumentId, boolean halted) implements Command {}
}
