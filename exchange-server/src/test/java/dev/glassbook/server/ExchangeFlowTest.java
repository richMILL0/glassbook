package dev.glassbook.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExchangeFlowTest {

    private static final Path DATA_DIR = tempDir();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("glassbook.engine.data-dir", DATA_DIR::toString);
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("glassbook-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    TestRestTemplate rest;

    record Session(String token, long participantId) { }

    @SuppressWarnings("unchecked")
    private Session register(String prefix) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> response = rest.postForEntity("/api/auth/register",
                Map.of("username", name, "password", "correct-horse-battery"), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        return new Session((String) body.get("token"), ((Number) body.get("participantId")).longValue());
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, String token, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) headers.setBearerAuth(token);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }

    private Map<String, Object> order(String symbol, String side, String type, String tif, long price, long qty,
                                      String clientId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("symbol", symbol);
        body.put("side", side);
        body.put("type", type);
        body.put("timeInForce", tif);
        body.put("price", price);
        body.put("quantity", qty);
        if (clientId != null) body.put("clientOrderId", clientId);
        return body;
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoTradersTradeAndBalancesFollow() {
        Session alice = register("alice");
        Session bob = register("bob");

        ResponseEntity<Map> sell = call(HttpMethod.POST, "/api/orders", alice.token(),
                order("GLSS", "SELL", "LIMIT", "GTC", 10_100, 10, null), Map.class);
        assertThat(sell.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sell.getBody().get("status")).isEqualTo("OPEN");

        ResponseEntity<Map> buy = call(HttpMethod.POST, "/api/orders", bob.token(),
                order("GLSS", "BUY", "MARKET", "IOC", 0, 4, null), Map.class);
        assertThat(buy.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(buy.getBody().get("status")).isEqualTo("FILLED");
        assertThat(((Number) buy.getBody().get("averagePrice")).longValue()).isEqualTo(10_100);

        Map<String, Object> bobAccount = call(HttpMethod.GET, "/api/account", bob.token(), null, Map.class).getBody();
        assertThat(((Number) bobAccount.get("cashFree")).longValue()).isEqualTo(1_000_000_000L - 4 * 10_100);
        List<Map<String, Object>> positions = (List<Map<String, Object>>) bobAccount.get("positions");
        Map<String, Object> glss = positions.stream().filter(p -> p.get("symbol").equals("GLSS")).findFirst().get();
        assertThat(((Number) glss.get("free")).longValue()).isEqualTo(1_004);

        Map<String, Object> book = rest.getForObject("/api/market/GLSS/book", Map.class);
        List<Map<String, Object>> asks = (List<Map<String, Object>>) book.get("asks");
        assertThat(asks).isNotEmpty();
        assertThat(((Number) asks.get(0).get("quantity")).longValue()).isGreaterThanOrEqualTo(6);
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientOrderIdMakesRetriesSafe() {
        Session carol = register("carol");
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = order("MERI", "BUY", "LIMIT", "GTC", 40_000, 3, key);

        ResponseEntity<Map> first = call(HttpMethod.POST, "/api/orders", carol.token(), body, Map.class);
        ResponseEntity<Map> second = call(HttpMethod.POST, "/api/orders", carol.token(), body, Map.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("status")).isEqualTo("DUPLICATE");
        assertThat(second.getBody().get("orderId")).isEqualTo(first.getBody().get("orderId"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void riskChecksRejectOrdersTheAccountCannotCover() {
        Session dave = register("dave");

        ResponseEntity<Map> tooBig = call(HttpMethod.POST, "/api/orders", dave.token(),
                order("GLSS", "BUY", "LIMIT", "GTC", 10_000, 1_000_000, null), Map.class);
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(tooBig.getBody().get("rejectReason")).isEqualTo("INSUFFICIENT_FUNDS");

        ResponseEntity<Map> offTick = call(HttpMethod.POST, "/api/orders", dave.token(),
                order("MERI", "BUY", "LIMIT", "GTC", 40_003, 1, null), Map.class);
        assertThat(offTick.getBody().get("rejectReason")).isEqualTo("TICK_SIZE_VIOLATION");
    }

    @Test
    void securityRules() {
        assertThat(call(HttpMethod.POST, "/api/orders", null,
                order("GLSS", "BUY", "LIMIT", "GTC", 10_000, 1, null), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/instruments", List.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        Session trader = register("eve");
        assertThat(call(HttpMethod.GET, "/api/admin/audit", trader.token(), null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void operatorAuditConfirmsTheBooksBalance() {
        Session a = register("frank");
        Session b = register("grace");
        call(HttpMethod.POST, "/api/orders", a.token(), order("KRNL", "SELL", "LIMIT", "GTC", 2_500, 100, null), Map.class);
        call(HttpMethod.POST, "/api/orders", b.token(), order("KRNL", "BUY", "MARKET", "IOC", 0, 50, null), Map.class);

        ResponseEntity<Map> login = rest.postForEntity("/api/auth/login",
                Map.of("username", "operator", "password", "operator-change-me"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String operatorToken = (String) login.getBody().get("token");

        ResponseEntity<Map> audit = call(HttpMethod.GET, "/api/admin/audit", operatorToken, null, Map.class);
        assertThat(audit.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> engine = (Map<String, Object>) audit.getBody().get("engine");
        assertThat(engine.get("ok")).isEqualTo(true);
    }
}
