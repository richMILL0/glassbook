package dev.glassbook.server.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbook.server.market.Views.TickerView;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Component
public class MarketWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MarketWebSocketHandler.class);
    private static final int BOOK_LEVELS = 15;

    private static final class Client {
        final WebSocketSession session;
        final Set<String> symbols = ConcurrentHashMap.newKeySet();

        Client(WebSocketSession session) {
            this.session = session;
        }
    }

    private final MarketDataService marketData;
    private final ObjectMapper mapper;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();

    public MarketWebSocketHandler(MarketDataService marketData, ObjectMapper mapper, MeterRegistry meters) {
        this.marketData = marketData;
        this.mapper = mapper;
        Gauge.builder("glassbook.websocket.sessions", clients, Map::size).register(meters);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        clients.put(session.getId(), new Client(new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        clients.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        Client client = clients.get(session.getId());
        if (client == null) return;
        JsonNode node = mapper.readTree(message.getPayload());
        String op = node.path("op").asText("");
        String symbol = node.path("symbol").asText("").toUpperCase();
        if (op.equals("subscribe") && marketData.ticker(symbol) != null) {
            client.symbols.add(symbol);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("type", "snapshot");
            snapshot.put("symbol", symbol);
            snapshot.put("ticker", marketData.ticker(symbol));
            snapshot.put("book", marketData.book(symbol, BOOK_LEVELS));
            snapshot.put("trades", marketData.trades(symbol, 50));
            snapshot.put("candles", marketData.allCandles(symbol, 200));
            send(client, snapshot);
        } else if (op.equals("unsubscribe")) {
            client.symbols.remove(symbol);
        }
    }

    @Scheduled(fixedRate = 100)
    void pushUpdates() {
        List<MarketDataService.Update> updates = marketData.drainUpdates(BOOK_LEVELS);
        if (updates.isEmpty() || clients.isEmpty()) return;
        for (MarketDataService.Update u : updates) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "update");
            message.put("symbol", u.symbol());
            if (u.book() != null) message.put("book", u.book());
            if (!u.trades().isEmpty()) message.put("trades", u.trades());
            if (u.candles() != null) message.put("candles", u.candles());
            TickerView ticker = marketData.ticker(u.symbol());
            message.put("ticker", ticker);
            String json = toJson(message);
            if (json == null) continue;
            for (Client c : clients.values()) {
                if (c.symbols.contains(u.symbol())) sendRaw(c, json);
            }
        }
    }

    private void send(Client client, Object payload) {
        String json = toJson(payload);
        if (json != null) sendRaw(client, json);
    }

    private void sendRaw(Client client, String json) {
        try {
            if (client.session.isOpen()) client.session.sendMessage(new TextMessage(json));
        } catch (Exception e) {
            log.debug("dropping websocket session {}: {}", client.session.getId(), e.toString());
            clients.remove(client.session.getId());
            try {
                client.session.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {

            }
        }
    }

    private String toJson(Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("cannot serialize websocket payload", e);
            return null;
        }
    }
}
