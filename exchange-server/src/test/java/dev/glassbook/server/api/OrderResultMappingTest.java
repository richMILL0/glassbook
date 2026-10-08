package dev.glassbook.server.api;

import dev.glassbook.core.engine.CommandResult;
import dev.glassbook.core.model.CancelReason;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.RejectReason;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderResultMappingTest {

    private static final Command.PlaceOrder BUY_10 = new Command.PlaceOrder(1, 1, "", Side.BUY, OrderType.LIMIT,
            TimeInForce.GTC, 100, 10);

    private static Event.OrderAccepted accepted(long id) {
        return new Event.OrderAccepted(5, 0, id, 1, 1, "", Side.BUY, OrderType.LIMIT, TimeInForce.GTC, 100, 10);
    }

    private static Event.TradeExecuted trade(long id, long price, long qty) {
        return new Event.TradeExecuted(5, 0, id, 1, price, qty, 7, 8, 1, 2, Side.BUY);
    }

    @Test
    void restingOrderIsOpen() {
        var result = OrderController.toResult(new CommandResult(5, List.of(accepted(7))), BUY_10);
        assertThat(result.status()).isEqualTo("OPEN");
        assertThat(result.restingQuantity()).isEqualTo(10);
        assertThat(result.orderId()).isEqualTo(7);
    }

    @Test
    void fullyFilledOrderReportsAveragePrice() {
        var events = List.<Event>of(accepted(7), trade(1, 100, 4), trade(2, 110, 6));
        var result = OrderController.toResult(new CommandResult(5, events), BUY_10);
        assertThat(result.status()).isEqualTo("FILLED");
        assertThat(result.filledQuantity()).isEqualTo(10);
        assertThat(result.averagePrice()).isEqualTo(106);           // (4*100 + 6*110) / 10
        assertThat(result.fills()).hasSize(2);
    }

    @Test
    void partiallyFilledThenCancelledKeepsTheFillAndTheReason() {
        var events = List.<Event>of(accepted(7), trade(1, 100, 4),
                new Event.OrderCancelled(5, 0, 7, 1, 1, 6, CancelReason.IOC_REMAINDER));
        var result = OrderController.toResult(new CommandResult(5, events), BUY_10);
        assertThat(result.status()).isEqualTo("PARTIALLY_FILLED");
        assertThat(result.cancelledQuantity()).isEqualTo(6);
        assertThat(result.restingQuantity()).isEqualTo(0);
        assertThat(result.cancelReason()).isEqualTo("IOC_REMAINDER");
    }

    @Test
    void rejectionAndDuplicate() {
        var rejected = new Event.OrderRejected(5, 0, 1, 1, "", RejectReason.INSUFFICIENT_FUNDS, 0);
        assertThat(OrderController.toResult(new CommandResult(5, List.of(rejected)), BUY_10).rejectReason())
                .isEqualTo("INSUFFICIENT_FUNDS");

        var duplicate = new Event.OrderRejected(6, 0, 1, 1, "k", RejectReason.DUPLICATE_CLIENT_ORDER_ID, 42);
        var result = OrderController.toResult(new CommandResult(6, List.of(duplicate)), BUY_10);
        assertThat(result.status()).isEqualTo("DUPLICATE");
        assertThat(result.orderId()).isEqualTo(42);
    }
}