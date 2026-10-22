package dev.glassbook.bench;

import dev.glassbook.core.engine.Exchange;
import dev.glassbook.core.model.Command;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)                                /// У каждого потока замера свой экземпляр
@BenchmarkMode({Mode.Throughput, Mode.AverageTime}) /// Замеряется пропускная способность и среднее время
@OutputTimeUnit(TimeUnit.MICROSECONDS)              /// Печать в микросекундах
@Warmup(iterations = 3, time = 2)                   /// Ждет 6 секунд, чтобы "прогресться"
@Measurement(iterations = 5, time = 3)              /// 15 секунд идут в отчет
@Fork(2)                                            /// Будет 2 отдельных запуска, чтобы добиться точного результата
public class MatchingBenchmark {

    private Exchange exchange;
    private Workload workload;
    private long seq;
    private long ts;

    @Setup(Level.Trial)     /// Вызывается 1 раз(подготовочный)
    public void setup() {
        exchange = new Exchange(Workload.INSTRUMENTS, 100_000);
        workload = new Workload(42);
        seq = 0;
        ts = 1_000;
        for (Command c : Workload.funding()) exchange.apply(++seq, ++ts, c, workload::observe);
    }

    @Benchmark              /// Много раз прогоняет этот метод
    public void applyMixedFlow() {
        exchange.apply(++seq, ++ts, workload.next(), workload::observe);
    }
}
