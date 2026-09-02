package dev.glassbook.core.engine;

import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.RejectReason;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

public record CommandResult(long seq, List<Event> events) {

    public Optional<Event.OrderRejected> orderRejection() {
        return events.stream().filter(Event.OrderRejected.class::isInstance).map(Event.OrderRejected.class::cast)
                .findFirst();
    }

    public Optional<Event.CommandRejected> commandRejection() {
        return events.stream().filter(Event.CommandRejected.class::isInstance).map(Event.CommandRejected.class::cast)
                .findFirst();
    }

    public Optional<RejectReason> rejectReason() {
        return orderRejection().map(Event.OrderRejected::reason)
                .or(() -> commandRejection().map(Event.CommandRejected::reason));
    }

    public OptionalLong acceptedOrderId() {
        return events.stream().filter(Event.OrderAccepted.class::isInstance).map(Event.OrderAccepted.class::cast)
                .mapToLong(Event.OrderAccepted::orderId).findFirst();
    }

    public boolean isRejected() {
        return rejectReason().isPresent();
    }
}
