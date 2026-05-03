package io.canonlog.sample

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureNanoTime

/**
 * In-process load test for the full canonical-log stack.
 *
 * What this asserts is **concurrency correctness**, not throughput: every request
 * produces exactly one canonical line, no line shares a `work_unit_id` with another
 * (i.e. no double-emit), every line's per-request fields match the request that
 * produced them, and steady-state contributor counters land at the same value on
 * every line (any distribution = field bleeding between concurrent requests = bug).
 *
 * The original `ab -n 1000 -c 10` ritual documented in CLAUDE.md was checking
 * exactly this. Reproducing the throughput numbers in CI is not feasible — runner
 * variance is too high to use them as a regression signal — so this test logs
 * throughput informationally without asserting on it. That number is useful for a
 * developer eyeballing PR-time effects on overhead, not for gate-keeping.
 *
 * Tuning knobs ([REQUESTS], [CONCURRENCY], [WARMUP]) chosen to keep CI runtime
 * bounded while still exercising enough fan-out to surface scheduler-dependent
 * races. Bumping them locally is fine; just don't crank the asserted-on knobs in
 * a way that makes the test flaky on shared CI hardware.
 */
class FullStackLoadTest : DescribeSpec({

    var app: ConfigurableApplicationContext? = null
    var port = 0
    val appender = ListAppender<ILoggingEvent>()
    val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(CONCURRENCY, 5, TimeUnit.MINUTES))
        .build()

    beforeSpec {
        app = SpringApplication.run(
            Application::class.java,
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:load-${System.nanoTime()};DB_CLOSE_DELAY=-1",
        )
        port = app!!.environment.getProperty("local.server.port", Int::class.java)!!

        // Attach the appender AFTER Spring boots — see FullStackEndToEndTest for the
        // gotcha rationale (Spring Boot resets logback during init).
        appender.start()
        val canonical = LoggerFactory.getLogger("canonical") as LogbackLogger
        canonical.addAppender(appender)
        canonical.level = Level.INFO

        // Warmup: prime the JIT, the connection pool, the DB pool, and Spring's lazy
        // dispatcher init. Cleared from the appender before the measured run so we
        // only assert on the steady-state requests.
        repeat(WARMUP) { fireRequest(client, port) }
        appender.list.clear()
    }

    afterSpec {
        app?.close()
        (LoggerFactory.getLogger("canonical") as LogbackLogger).detachAppender(appender)
    }

    describe("Full-stack load: GET /posts/1 under concurrency") {
        it("emits exactly N canonical lines with no field bleeding under N concurrent requests") {
            val durations = ConcurrentLinkedQueue<Long>()
            val errors = AtomicInteger(0)

            val totalNs = measureNanoTime {
                coroutineScope {
                    val sem = Semaphore(CONCURRENCY)
                    (1..REQUESTS).map {
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                val started = System.nanoTime()
                                runCatching { fireRequest(client, port) }
                                    .onSuccess { durations.add(System.nanoTime() - started) }
                                    .onFailure { errors.incrementAndGet() }
                            }
                        }
                    }.awaitAll()
                }
            }

            errors.get() shouldBe 0

            // Give logback a beat to settle in case any appender in the chain is async.
            // The canonical line is logged synchronously via StructuredArguments inside
            // the filter, but defensively wait for the in-memory list to settle.
            withContext(Dispatchers.IO) { Thread.sleep(50) }

            val lines = allCanonicalSnapshots(appender)

            // Exactly one canonical line per request.
            lines.size shouldBe REQUESTS

            // Each work unit produced its own line — no double-emit, no merged work units.
            lines.map { it["work_unit_id"] as String }.toSet().size shouldBe REQUESTS

            // Per-request fields match the request that produced them. If concurrent
            // requests' contributions bled into each other, post_id or status would
            // diverge (the field-bleeding signal CLAUDE.md describes).
            lines.all { it["post_id"] == 1L } shouldBe true
            lines.all { it["http_response_status_code"] == 200 } shouldBe true
            lines.all { it["http_route"] == "/posts/{id}" } shouldBe true
            lines.all { it["url_path"] == "/posts/1" } shouldBe true

            // Steady-state counters: every request to /posts/1 hits the same number of
            // DB queries and outbound calls. A value distribution here would mean
            // contributions leaked between requests' accumulators.
            lines.distinctValuesOf("db_query_count").size shouldBe 1
            lines.distinctValuesOf("db_execution_count").size shouldBe 1
            lines.distinctValuesOf("http_client_request_count").size shouldBe 1

            // Throughput report — informational, not asserted. Eyeball this on PRs to
            // catch surprising changes in per-request overhead.
            val totalMs = totalNs / 1_000_000.0
            val sorted = durations.toList().sorted()
            fun pct(p: Int): Double = sorted[((sorted.size - 1) * p / 100)] / 1_000_000.0
            println(
                "[load] $REQUESTS reqs / concurrency=$CONCURRENCY: " +
                    "wall=${"%.0f".format(totalMs)}ms, " +
                    "throughput=${"%.0f".format(REQUESTS * 1000.0 / totalMs)} req/s, " +
                    "p50=${"%.1f".format(pct(50))}ms, " +
                    "p95=${"%.1f".format(pct(95))}ms, " +
                    "p99=${"%.1f".format(pct(99))}ms",
            )
        }
    }
}) {
    private companion object {
        const val REQUESTS = 500
        const val CONCURRENCY = 25
        const val WARMUP = 50
    }
}

private fun fireRequest(client: OkHttpClient, port: Int) {
    val req = Request.Builder().url("http://localhost:$port/posts/1").build()
    client.newCall(req).execute().use { check(it.code == 200) { "expected 200, got ${it.code}" } }
}

private fun List<Map<String, Any>>.distinctValuesOf(key: String): Set<Any> =
    mapNotNullTo(mutableSetOf()) { it[key] }
