package io.github.alexhumphreys.canonicallog;

import kotlin.Unit;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the Java ergonomics of the public API: statics without {@code INSTANCE},
 * {@code increment} without the default arg, Map-based {@code markFailed}/{@code markDegraded}
 * without {@code kotlin.Pair}, and {@code withCanonicalLogBlocking} driven by Java lambdas.
 * JUnit (not Kotest) because the whole point is that this file is Java.
 */
class JavaErgonomicsTest {

    private static final WorkUnitAdapter<String> ADAPTER = new WorkUnitAdapter<>() {
        @Override
        public WorkUnit describe(String input) {
            return new WorkUnit("java-smoke", "test", Instant.now());
        }

        @Override
        public void enrich(CanonicalLogContext ctx, String input, Outcome outcome) {
            ctx.put("outcome_class", outcome.getClass().getSimpleName());
        }
    };

    private static Map<String, Object> capture(Consumer<CanonicalLogContext> body) {
        return capture(ADAPTER, body);
    }

    private static Map<String, Object> capture(
            WorkUnitAdapter<String> adapter, Consumer<CanonicalLogContext> body) {
        AtomicReference<Map<String, Object>> line = new AtomicReference<>();
        WithCanonicalLogKt.withCanonicalLogBlocking(
                adapter,
                "input",
                ctx -> {
                    line.set(ctx.snapshot());
                    return Unit.INSTANCE;
                },
                ctx -> {
                    body.accept(ctx);
                    return Unit.INSTANCE;
                });
        return line.get();
    }

    /**
     * A Java adapter that overrides only {@code describe} and {@code enrich} — {@code seed}
     * is the SPI's default method. That this compiles at all is the point: the Kotlin
     * interface default method must be a real JVM default method for Java implementers, so
     * existing Java adapters keep compiling after {@code seed} was added.
     */
    private static final WorkUnitAdapter<String> NO_SEED_ADAPTER = new WorkUnitAdapter<>() {
        @Override
        public WorkUnit describe(String input) {
            return new WorkUnit("java-no-seed", "test", Instant.now());
        }

        @Override
        public void enrich(CanonicalLogContext ctx, String input, Outcome outcome) {
            ctx.put("enriched", true);
        }
    };

    /** A Java adapter that DOES override {@code seed}, to prove the override path is callable from Java. */
    private static final WorkUnitAdapter<String> SEEDING_ADAPTER = new WorkUnitAdapter<>() {
        @Override
        public WorkUnit describe(String input) {
            return new WorkUnit("java-seed", "test", Instant.now());
        }

        @Override
        public void seed(CanonicalLogContext ctx, String input) {
            ctx.put("seeded_field", "from_seed");
        }

        @Override
        public void enrich(CanonicalLogContext ctx, String input, Outcome outcome) {
            ctx.put("enriched", true);
        }
    };

    @Test
    void ambientApiIsCallableAsPlainStatics() {
        Map<String, Object> line = capture(ctx -> {
            CanonicalLog.put("post_id", 42L);
            CanonicalLog.increment("cache_hit_count");
            CanonicalLog.increment("cache_hit_count", 2L);
            CanonicalLog.markFailed("post_not_found", Map.of("post_lookup_ms", 7L));
        });

        assertEquals(42L, line.get("post_id"));
        assertEquals(3L, line.get("cache_hit_count"));
        assertEquals(true, line.get("error"));
        assertEquals("post_not_found", line.get("error_reason"));
        assertEquals(7L, line.get("post_lookup_ms"));
        assertEquals("Completed", line.get("outcome_class"));
    }

    @Test
    void markDegradedTakesAMapAndDoesNotSetError() {
        Map<String, Object> line = capture(ctx ->
                CanonicalLog.markDegraded("cache_stale", Map.of("cache_age_ms", 1234L)));

        assertEquals(true, line.get("degraded"));
        assertEquals("cache_stale", line.get("degraded_reason"));
        assertEquals(1234L, line.get("cache_age_ms"));
        assertNull(line.get("error"));
    }

    @Test
    void contextMethodsAreJavaFriendlyToo() {
        Map<String, Object> line = capture(ctx -> {
            ctx.increment("db_query_count");
            ctx.markFailed("replica_down", Map.of("replica", "eu-1"));
            ctx.markDegraded("served_from_cache", Map.of("cache_hit", true));
        });

        assertEquals(1L, line.get("db_query_count"));
        assertEquals("replica_down", line.get("error_reason"));
        assertEquals("eu-1", line.get("replica"));
        assertEquals("served_from_cache", line.get("degraded_reason"));
        assertEquals(true, line.get("cache_hit"));
    }

    @Test
    void javaAdapterCompilesWithoutOverridingSeedAndRunsNormally() {
        Map<String, Object> line = capture(NO_SEED_ADAPTER, ctx -> CanonicalLog.put("post_id", 7L));

        assertEquals(7L, line.get("post_id"));
        assertEquals(true, line.get("enriched"));
        // The default seed is a no-op, so it never records a seed error.
        assertNull(line.get("canonical_log_seed_error"));
    }

    @Test
    void javaAdapterCanOverrideSeed() {
        Map<String, Object> line = capture(SEEDING_ADAPTER, ctx -> {});

        assertEquals("from_seed", line.get("seeded_field"));
        assertEquals(true, line.get("enriched"));
        assertNull(line.get("canonical_log_seed_error"));
    }
}
