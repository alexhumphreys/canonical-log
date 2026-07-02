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
        AtomicReference<Map<String, Object>> line = new AtomicReference<>();
        WithCanonicalLogKt.withCanonicalLogBlocking(
                ADAPTER,
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
}
