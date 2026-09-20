package dev.glassbook.server.config;

import dev.glassbook.core.model.Instrument;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class InstrumentCatalog {

    public record Entry(Instrument instrument, String name, long initialPrice) {}

    private final Map<String, Entry> bySymbol = new LinkedHashMap<>();
    private final Map<Integer, Entry> byId = new LinkedHashMap<>();

    public InstrumentCatalog(GlassbookProperties properties) {
        if (properties.instruments() == null || properties.instruments().isEmpty()) {
            throw new IllegalStateException("Glassbook.instruments не может быть пустым");
        }

        for (GlassbookProperties.InstrumentConfig c : properties.instruments()) {
            Instrument instrument = new Instrument(c.id(), c.symbol(), c.tickSize(), c.lotSize(), c.priceScale());
            Entry entry = new Entry(instrument, c.name(), c.initialPrice());
            bySymbol.put(c.symbol(), entry);
            byId.put(c.id(), entry);
        }
    }

    public List<Instrument> instruments() {
        return byId.values().stream().map(Entry::instrument).toList();
    }

    public List<Entry> entries() {
        return List.copyOf(byId.values());
    }

    public Entry bySymbol(String symbol) {
        return symbol == null ? null : bySymbol.get(symbol.toUpperCase());
    }

    public Entry byId(int id) {
        return byId.get(id);
    }

    public Map<Integer, Entry> asMap() {
        return Collections.unmodifiableMap(byId);
    }
}
