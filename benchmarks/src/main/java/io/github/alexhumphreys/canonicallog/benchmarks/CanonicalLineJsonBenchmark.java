package io.github.alexhumphreys.canonicallog.benchmarks;

import io.github.alexhumphreys.canonicallog.JsonCanonicalLineWriterKt;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Rendering cost of the hand-rolled JSON sink, once per work unit at emit time.
 *
 * <p>{@code escaping=true} makes every string value carry a quote and a newline, which
 * forces the per-character escape path rather than the bulk-append fast path — the
 * difference between the two is what says whether hostile field values (a user-supplied
 * string landing on a canonical line) cost meaningfully more than clean ones.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class CanonicalLineJsonBenchmark {

    @Param({"10", "40"})
    public int fields;

    @Param({"false", "true"})
    public boolean escaping;

    private Map<String, Object> snapshot;

    @Setup(Level.Trial)
    public void buildSnapshot() {
        snapshot = new HashMap<>();
        for (int i = 0; i < fields; i++) {
            if (i % 3 == 0) {
                snapshot.put("field_" + i, escaping ? "value \" with \n escapes " + i : "value_" + i);
            } else if (i % 3 == 1) {
                snapshot.put("field_" + i, (long) i);
            } else {
                snapshot.put("field_" + i, i % 2 == 0);
            }
        }
    }

    @Benchmark
    public String render() {
        return JsonCanonicalLineWriterKt.canonicalLineJson(snapshot);
    }
}
