package dev.glassbook.server.pipeline;

import dev.glassbook.core.model.Event;

import java.util.List;

public record EventGroup(long seq, List<Event> events) { }
