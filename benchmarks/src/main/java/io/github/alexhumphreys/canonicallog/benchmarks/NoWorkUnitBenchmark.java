package io.github.alexhumphreys.canonicallog.benchmarks;

import io.github.alexhumphreys.canonicallog.CanonicalLog;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * The cost paid by code that is <em>not</em> inside a work unit: app startup, library
 * internals, unit tests that open no unit. Every {@code CanonicalLog} call on such a
 * path is a threadlocal read and a null check, and this is the number that says whether
 * an adopter can sprinkle contributions freely without thinking about which paths are
 * instrumented.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class NoWorkUnitBenchmark {

    @Benchmark
    public void putWithNoUnitOpen() {
        CanonicalLog.put("key", "value");
    }

    @Benchmark
    public void incrementWithNoUnitOpen() {
        CanonicalLog.increment("count", 1L);
    }
}
