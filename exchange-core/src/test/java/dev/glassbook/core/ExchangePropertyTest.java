package dev.glassbook.core;

import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.OrderType;
import dev.glassbook.core.model.Side;
import dev.glassbook.core.model.TimeInForce;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExchangePropertyTest {

    record Step(int kind, int participant, int instrument, boolean buy, long priceOffset, long qty) {}

    @Provide
    Arbitrary<List<Step>> scripts() {
        Arbitrary<Step> step = Combinators.combine(
                Arbitraries.integers().between(0, 6),
                Arbitraries.integers().between(1, 4),
                Arbitraries.integers().between(1, 2),
                Arbitraries.of(true, false),
                Arbitraries.longs().between(-10, 10),
                Arbitraries.longs().between(0, 40)
        ).as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(120);
    }

    @Provide
    Arbitrary<List<Step>> validScripts() {
        Arbitrary<Step> step = Combinators.combine(
                Arbitraries.integers().between(0, 5),
                Arbitraries.integers().between(1, 4),
                Arbitraries.just(1),
                Arbitraries.of(true, false),
                Arbitraries.longs().between(-10, 10),
                Arbitraries.longs().between(1, 40)
        ).as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(150);
    }

    private static Command toCommand(Step s) {
        long mid = s.instrument() == 1 ? 1_000 : 5_000;
        long tick = s.instrument() == 1 ? 1 : 5;
        long price = mid + s.priceOffset() * tick;
        Side side = s.buy() ? Side.BUY : Side.SELL;
        return switch (s.kind()) {
            case 0 -> new Command.PlaceOrder(s.participant(), s.instrument(), "", side, OrderType.LIMIT, TimeInForce.GTC, price, s.qty());
            case 1 -> new Command.PlaceOrder(s.participant(), s.instrument(), "", side, OrderType.MARKET, TimeInForce.IOC, 0, s.qty());
            case 2 -> new Command.PlaceOrder(s.participant(), s.instrument(), "", side, OrderType.LIMIT, TimeInForce.IOC, price, s.qty());
            case 3 -> new Command.PlaceOrder(s.participant(), s.instrument(), "", side, OrderType.LIMIT, TimeInForce.FOK, price, s.qty());
            case 4 -> new Command.PlaceOrder(s.participant(), s.instrument(), "", side, OrderType.LIMIT, TimeInForce.POST_ONLY, price, s.qty());
            case 5 -> new Command.CancelOrder(s.participant(), s.qty());
            default -> new Command.Deposit(s.participant(), (int) (s.qty() % 3), 1 + s.qty());
        };
    }

    @Property(tries = 400)
    void invariantsSurviveAnyCommandSequence(@ForAll("scripts") List<Step> steps) {
        TestExchange t = new TestExchange();
        for (long p = 1; p <= 4; p++) {
            t.apply(new Command.Deposit(p, 0, 200_000));
            t.apply(new Command.Deposit(p, 1, 150));
            t.apply(new Command.Deposit(p, 2, 400));
        }
        for (Step s : steps) {
            t.apply(toCommand(s));
            t.checkInvariants();
        }
    }

    @Property(tries = 400)
    void engineAgreesWithNaiveReference(@ForAll("validScripts") List<Step> steps) {
        TestExchange engine = new TestExchange();
        ReferenceExchange reference = new ReferenceExchange();
        for (long p = 1; p <= 4; p++) {
            Command cash = new Command.Deposit(p, 0, 10_000_000_000_000L);
            Command shares = new Command.Deposit(p, 1, 100_000_000L);
            engine.apply(cash);
            engine.apply(shares);
            reference.apply(cash);
            reference.apply(shares);
        }
        for (Step s : steps) {
            Command c = toCommand(s);
            engine.apply(c);
            reference.apply(c);
        }
        assertEquals(reference.tape(), ReferenceExchange.tapeOf(engine.events));
        engine.checkInvariants();
    }
}
