package dev.glassbook.server.api;

import dev.glassbook.core.engine.CommandResult;
import dev.glassbook.core.engine.ExchangeCore;
import dev.glassbook.core.model.CancelReason;
import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Event;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.RejectReason;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;
import dev.glassbook.server.config.InstrumentCatalog;
import dev.glassbook.server.projection.OrderRecord;
import dev.glassbook.server.projection.OrderRepository;
import dev.glassbook.server.projection.OrderStatus;
import dev.glassbook.server.projection.TradeRecord;
import dev.glassbook.server.projection.TradeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
@Validated
public class OrderController {

    public record PlaceOrderRequest(
            @NotBlank String symbol,
            @NotNull Side side,
            @NotNull OrderType type,
            TimeInForce timeInForce,
            @Min(0) long price,
            @Min(1) long quantity,
            @Size(max = 64) String clientOrderId) { }

    public record FillView(long tradeId, long price, long quantity) { }

    public record OrderResult(String status, Long orderId, String rejectReason, long filledQuantity,
                              long averagePrice, long restingQuantity, long cancelledQuantity, String cancelReason,
                              List<FillView> fills) { }

    public record CancelResult(boolean cancelled, long orderId, long remainingQuantity) { }

    public record OrderView(long id, String symbol, String side, String type, String timeInForce, long price,
                            long quantity, long filledQuantity, String status, String cancelReason,
                            Instant createdAt, Instant updatedAt) { }

    public record TradeView(long id, String symbol, long price, long quantity, String side, String role,
                            Instant executedAt) { }

    private final ExchangeCore core;
    private final InstrumentCatalog catalog;
    private final OrderRepository orders;
    private final TradeRepository trades;
    private final MeterRegistry meters;
    private final Timer latency;

    public OrderController(ExchangeCore core, InstrumentCatalog catalog, OrderRepository orders,
                           TradeRepository trades, MeterRegistry meters) {
        this.core = core;
        this.catalog = catalog;
        this.orders = orders;
        this.trades = trades;
        this.meters = meters;
        this.latency = Timer.builder("glassbook.order.latency")
                .description("time from HTTP handler to durable, applied order result")
                .publishPercentileHistogram().register(meters);
    }

    @PostMapping("/orders")
    public CompletableFuture<ResponseEntity<OrderResult>> place(@AuthenticationPrincipal Jwt jwt,
                                                                @Valid @RequestBody PlaceOrderRequest request) {
        long participant = Long.parseLong(jwt.getSubject());
        InstrumentCatalog.Entry entry = catalog.bySymbol(request.symbol());
        if (entry == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_INSTRUMENT", "Не найден инструмент");
        }

        TimeInForce tif = request.timeInForce() == null ? TimeInForce.GTC : request.timeInForce();
        Command.PlaceOrder command = new Command.PlaceOrder(participant, entry.instrument().id(),
                request.clientOrderId(), request.side(), request.type(), tif, request.price(), request.quantity());

        Timer.Sample sample = Timer.start(meters);
        return core.submit(command).whenComplete((r, e) -> sample.stop(latency)).thenApply(result -> {
            OrderResult body = toResult(result, command);
            meters.counter("glassbook.orders", "outcome", body.status()).increment();
            HttpStatus status = switch (body.status()) {
                case "REJECTED" -> HttpStatus.UNPROCESSABLE_ENTITY;
                case "DUPLICATE" -> HttpStatus.OK;
                default -> HttpStatus.CREATED;
            };
            return ResponseEntity.status(status).body(body);
        });
    }

    @DeleteMapping("/orders/{id}")
    public CompletableFuture<ResponseEntity<CancelResult>> cancel(@AuthenticationPrincipal Jwt jwt,
                                                                  @PathVariable long id) {
        long participant = Long.parseLong(jwt.getSubject());
        return core.submit(new Command.CancelOrder(participant, id)).thenApply(result -> {
            for (Event e : result.events()) {
                if (e instanceof Event.OrderCancelled c && c.reason() == CancelReason.USER) {
                    return ResponseEntity.ok(new CancelResult(true, id, c.remainingQuantity()));
                }
            }

            throw new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_ORDER", "Не найдена открытая заявка");
        });
    }

    @GetMapping("/orders")
    public List<OrderView> list(@AuthenticationPrincipal Jwt jwt,
                                @RequestParam(defaultValue = "open") String filter,
                                @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        long participant = Long.parseLong(jwt.getSubject());
        PageRequest page = PageRequest.of(0, limit);
        List<OrderRecord> rows = filter.equals("all")
                ? orders.findByParticipantIdOrderByIdDesc(participant, page)
                : orders.findByParticipantIdAndStatusInOrderByIdDesc(participant,
                        Set.of(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED), page);
        List<OrderView> out = new ArrayList<>(rows.size());
        for (OrderRecord r : rows) {
            out.add(new OrderView(r.getId(), r.getSymbol(), r.getSide(), r.getOrderType(), r.getTimeInForce(),
                    r.getPrice(), r.getQuantity(), r.getFilledQuantity(), r.getStatus().name(), r.getCancelReason(),
                    r.getCreatedAt(), r.getUpdatedAt()));
        }
        return out;
    }

    @GetMapping("/trades")
    public List<TradeView> myTrades(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        long participant = Long.parseLong(jwt.getSubject());
        List<TradeView> out = new ArrayList<>();
        for (TradeRecord t : trades.findMine(participant, PageRequest.of(0, limit))) {
            boolean bought = t.getBuyerId() == participant;
            String role = t.getTakerSide().equals(bought ? "BUY" : "SELL") ? "TAKER" : "MAKER";
            out.add(new TradeView(t.getId(), t.getSymbol(), t.getPrice(), t.getQuantity(), bought ? "BUY" : "SELL",
                    role, t.getExecutedAt()));
        }
        return out;
    }

    static OrderResult toResult(CommandResult result, Command.PlaceOrder command) {
        for (Event e : result.events()) {
            if (e instanceof Event.OrderRejected r) {
                if (r.reason() == RejectReason.DUPLICATE_CLIENT_ORDER_ID) {
                    return new OrderResult("DUPLICATE", r.existingOrderId(), null, 0, 0, 0, 0, null, List.of());
                }
                return new OrderResult("REJECTED", null, r.reason().name(), 0, 0, 0, 0, null, List.of());
            }
        }

        long orderId = result.acceptedOrderId().orElse(-1);
        List<FillView> fills = new ArrayList<>();

        long filled = 0;
        long notional = 0;
        long cancelled = 0;

        String cancelReason = null;
        for (Event e : result.events()) {
            if (e instanceof Event.TradeExecuted t) {
                fills.add(new FillView(t.tradeId(), t.price(), t.quantity()));
                filled += t.quantity();
                notional += t.price() * t.quantity();
            } else if (e instanceof Event.OrderCancelled c && c.orderId() == orderId) {
                cancelled = c.remainingQuantity();
                cancelReason = c.reason().name();
            }
        }

        long resting = command.quantity() - filled - cancelled;
        String status = filled == command.quantity() ? "FILLED"
                : filled > 0 ? "PARTIALLY_FILLED"
                : cancelled > 0 ? "CANCELLED" : "OPEN";
        return new OrderResult(status, orderId, null, filled, filled == 0 ? 0 : notional / filled, resting,
                cancelled, cancelReason, fills);
    }
}
