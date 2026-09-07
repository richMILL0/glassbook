package dev.glassbook.core;

import dev.glassbook.core.engine.ExchangeSnapshot;
import dev.glassbook.core.model.Command;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomizedInvariantsTest {

    @Test
    @DisplayName("book, balances and postings stay consistent under random traffic (many seeds)")
    void invariantsHoldAfterEveryCommand() {
        for (long seed = 1; seed <= 300; seed++) {
            List<Command> script = new ScenarioGenerator(seed, ScenarioGenerator.Mode.POOR).script(400);
            TestExchange t = new TestExchange();
            int step = 0;
            try {
                for (Command c : script) {
                    step++;
                    t.apply(c);
                    t.checkInvariants();
                }
            } catch (AssertionError | RuntimeException e) {
                throw new AssertionError("seed=" + seed + " step=" + step + " command=" + script.get(step - 1)
                        + ": " + e.getMessage(), e);
            }
        }
    }

    @Test
    @DisplayName("the exercise is not vacuous: random traffic produces trades, rests, cancels and rejections")
    void scenariosActuallyTrade() {
        long trades = 0;
        long rejections = 0;
        long cancels = 0;
        for (long seed = 1; seed <= 20; seed++) {
            TestExchange t = new TestExchange();
            new ScenarioGenerator(seed, ScenarioGenerator.Mode.POOR).script(400).forEach(t::apply);
            trades += t.events.stream().filter(e -> e instanceof dev.glassbook.core.model.Event.TradeExecuted).count();
            rejections += t.events.stream().filter(e -> e instanceof dev.glassbook.core.model.Event.OrderRejected).count();
            cancels += t.events.stream().filter(e -> e instanceof dev.glassbook.core.model.Event.OrderCancelled).count();
        }
        assertTrue(trades > 500, "too few trades: " + trades);
        assertTrue(rejections > 100, "too few rejections: " + rejections);
        assertTrue(cancels > 100, "too few cancels: " + cancels);
    }

    @Test
    @DisplayName("determinism: the same commands always lead to a bit-identical state")
    void deterministic() {
        for (long seed = 1; seed <= 25; seed++) {
            List<Command> script = new ScenarioGenerator(seed, ScenarioGenerator.Mode.POOR).script(500);
            TestExchange a = new TestExchange();
            TestExchange b = new TestExchange();
            script.forEach(a::apply);
            script.forEach(b::apply);
            assertEquals(ExchangeSnapshot.fingerprint(a.exchange), ExchangeSnapshot.fingerprint(b.exchange),
                    "seed " + seed);
            assertEquals(a.events, b.events, "seed " + seed);
        }
    }

    @Test
    @DisplayName("snapshot round trip: restored state is identical and keeps behaving identically")
    void snapshotRoundTrip() throws IOException {
        for (long seed = 1; seed <= 25; seed++) {
            List<Command> script = new ScenarioGenerator(seed, ScenarioGenerator.Mode.POOR).script(600);
            TestExchange live = new TestExchange();
            script.subList(0, 350).forEach(live::apply);

            byte[] bytes = ExchangeSnapshot.toBytes(live.exchange);
            var restored = ExchangeSnapshot.fromBytes(bytes, TestExchange.INSTRUMENTS, 10_000);
            assertEquals(ExchangeSnapshot.fingerprint(live.exchange), ExchangeSnapshot.fingerprint(restored),
                    "seed " + seed);
            Invariants.check(restored, null, null);

            long seq = live.exchange.lastSeq();
            for (Command c : script.subList(350, script.size())) {
                seq++;
                java.util.ArrayList<dev.glassbook.core.model.Event> fromLive = new java.util.ArrayList<>();
                java.util.ArrayList<dev.glassbook.core.model.Event> fromRestored = new java.util.ArrayList<>();
                live.exchange.apply(seq, 5_000 + seq, c, fromLive::add);
                restored.apply(seq, 5_000 + seq, c, fromRestored::add);
                assertEquals(fromLive, fromRestored, "seed " + seed + " seq " + seq);
            }
            assertEquals(ExchangeSnapshot.fingerprint(live.exchange), ExchangeSnapshot.fingerprint(restored));
        }
    }
}
