package dev.glassbook.core;

import dev.glassbook.core.model.Command;
import dev.glassbook.core.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifferentialTest {

    @Test
    @DisplayName("engine and naive reference produce the same tape and the same final book on 200 random streams")
    void engineMatchesReference() {
        long totalTrades = 0;
        for (long seed = 1; seed <= 200; seed++) {
            List<Command> script = new ScenarioGenerator(seed, ScenarioGenerator.Mode.RICH_VALID).script(500);
            TestExchange engine = new TestExchange();
            ReferenceExchange reference = new ReferenceExchange();

            for (Command c : script) {
                reference.apply(c);
                engine.apply(c);
            }

            List<String> expected = reference.tape();
            List<String> actual = ReferenceExchange.tapeOf(engine.events);
            assertEquals(expected.size(), actual.size(), "tape length, seed " + seed);
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i), actual.get(i), "seed " + seed + " tape entry " + i);
            }
            assertEquals(reference.finalBook(), finalBook(engine), "final book, seed " + seed);
            engine.checkInvariants();
            totalTrades += actual.stream().filter(s -> s.startsWith("T ")).count();
        }
        assertTrue(totalTrades > 2_000, "scenarios barely traded: " + totalTrades);
    }

    private static List<String> finalBook(TestExchange t) {
        List<String> out = new ArrayList<>();
        for (int inst = 1; inst <= 2; inst++) {
            for (Side side : Side.values()) {
                final int instrument = inst;
                t.exchange.book(inst).forEachOrder(side, o ->
                        out.add(instrument + " " + side + " " + o.id + ":" + o.remaining + "@" + o.price));
            }
        }
        return out;
    }
}
