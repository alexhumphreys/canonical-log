package io.github.alexhumphreys.canonicallog.benchmarks;

import io.github.alexhumphreys.canonicallog.CanonicalLogContext;
import io.github.alexhumphreys.canonicallog.Outcome;
import io.github.alexhumphreys.canonicallog.WithCanonicalLogKt;
import io.github.alexhumphreys.canonicallog.WorkUnit;
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter;
import kotlin.Unit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cost of one whole work unit: open, bind, MDC-mirror, contribute {@code fields} fields,
 * enrich, unbind, emit. This is the per-operation overhead an adopter pays on top of the
 * work the unit wraps, so it is the figure to quote against a request's own latency.
 *
 * <p>The emit is a counter rather than a real sink — the benchmark measures the
 * library's lifecycle, not the throughput of whatever the adopter logs to. Sink cost is
 * the adopter's choice and is measured separately by
 * {@link CanonicalLineJsonBenchmark}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class WorkUnitLifecycleBenchmark {

    /** 0 isolates the pure lifecycle; 10 is a realistic request's worth of fields. */
    @Param({"0", "10"})
    public int fields;

    private static final String[] KEYS = new String[32];

    static {
        for (int i = 0; i < KEYS.length; i++) {
            KEYS[i] = "field_" + i;
        }
    }

    private final AtomicLong emitted = new AtomicLong();

    private final WorkUnitAdapter<String> adapter = new WorkUnitAdapter<>() {
        @Override
        public WorkUnit describe(String input) {
            return new WorkUnit(input, "benchmark", Instant.EPOCH);
        }

        @Override
        public void seed(CanonicalLogContext ctx, String input) {
        }

        @Override
        public void enrich(CanonicalLogContext ctx, String input, Outcome outcome) {
            ctx.put("duration_ms", outcome.getDurationMs());
        }
    };

    @Benchmark
    public void blockingRoundTrip(Blackhole bh) {
        Object result = WithCanonicalLogKt.withCanonicalLogBlocking(
                adapter,
                "work-unit",
                ctx -> {
                    emitted.incrementAndGet();
                    return Unit.INSTANCE;
                },
                ctx -> {
                    for (int i = 0; i < fields; i++) {
                        ctx.put(KEYS[i], (long) i);
                    }
                    return Unit.INSTANCE;
                });
        bh.consume(result);
    }
}
