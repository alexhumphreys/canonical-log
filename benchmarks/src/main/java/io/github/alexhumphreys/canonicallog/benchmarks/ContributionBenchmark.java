package io.github.alexhumphreys.canonicallog.benchmarks;

import io.github.alexhumphreys.canonicallog.CanonicalLog;
import io.github.alexhumphreys.canonicallog.CanonicalLogContext;
import io.github.alexhumphreys.canonicallog.CanonicalLogElementKt;
import io.github.alexhumphreys.canonicallog.WorkUnit;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cost of a single field contribution to an open work unit — the operation an adopter
 * performs many times per request, and the one the library's "costs approximately
 * nothing" claim is really about.
 *
 * <p>Written in Java rather than Kotlin so JMH's annotation processor runs without
 * dragging kapt into the build; it also exercises the {@code @JvmStatic} surface Java
 * adopters actually call.
 *
 * <p>Run {@code -prof gc} to cross-check the byte figures asserted by
 * canonical-log-core's {@code AllocationBudgetTest}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class ContributionBenchmark {

    private CanonicalLogContext context;
    private CanonicalLogContext previous;

    @Setup(Level.Trial)
    @SuppressWarnings("deprecation") // the constructor is @DelicateCanonicalLogApi by design
    public void bind() {
        context = new CanonicalLogContext(new WorkUnit("bench", "benchmark", Instant.EPOCH));
        context.put("key", "value");
        context.increment("count", 1L);
        previous = CanonicalLogElementKt.bindCurrentCanonicalContext(context);
    }

    @TearDown(Level.Trial)
    public void unbind() {
        CanonicalLogElementKt.bindCurrentCanonicalContext(previous);
    }

    /** Ambient contribution with a unit open — the common case. */
    @Benchmark
    public void ambientPut() {
        CanonicalLog.put("key", "value");
    }

    /** Direct contribution, skipping the threadlocal lookup. */
    @Benchmark
    public void directPut() {
        context.put("key", "value");
    }

    /** Counter contribution: the {@code merge} path, with its boxed result. */
    @Benchmark
    public void ambientIncrement() {
        CanonicalLog.increment("count", 1L);
    }

    /** Emit-time snapshot of an open unit. */
    @Benchmark
    public void snapshot(Blackhole bh) {
        Map<String, Object> snapshot = context.snapshot();
        bh.consume(snapshot);
    }
}
