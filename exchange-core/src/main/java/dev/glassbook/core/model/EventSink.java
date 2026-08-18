package dev.glassbook.core.model;

@FunctionalInterface
public interface EventSink {
    void accept(Event event);

    default void commandApplied(long seq) { }

    EventSink NOOP = event -> { };
}
