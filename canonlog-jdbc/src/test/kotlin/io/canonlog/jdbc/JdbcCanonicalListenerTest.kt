package io.canonlog.jdbc

import io.canonlog.CanonicalLogContext
import io.canonlog.Outcome
import io.canonlog.WorkUnit
import io.canonlog.WorkUnitAdapter
import io.canonlog.withCanonicalLogBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

private val nullAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

private fun PostgreSQLContainer<*>.dataSource(): DataSource = PGSimpleDataSource().also {
    it.setURL(jdbcUrl)
    it.user = username
    it.password = password
}

class JdbcCanonicalListenerTest : DescribeSpec({

    val postgres = PostgreSQLContainer("postgres:16-alpine")

    beforeSpec { postgres.start() }
    afterSpec { postgres.stop() }

    describe("JdbcCanonicalListener") {
        it("counts queries and accumulates duration") {
            val ds = postgres.dataSource().withCanonicalLogging()
            var snap: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                ds.connection.use { conn ->
                    conn.createStatement().use { it.execute("SELECT 1") }
                    conn.createStatement().use { it.execute("SELECT 2") }
                    conn.createStatement().use { it.execute("SELECT 3") }
                }
            }

            // Three independent statements, three executions.
            snap["db_query_count"] shouldBe 3L
            snap["db_execution_count"] shouldBe 3L
            (snap["db_execution_duration_ms_total"] as Long).shouldBeGreaterThanOrEqual(0L)
            snap.containsKey("db_execution_error_count") shouldBe false
        }

        it("counts slow executions above the threshold") {
            // Threshold 0 makes every execution slow
            val listener = JdbcCanonicalListener(slowQueryThresholdMs = 0L)
            val ds = postgres.dataSource().withCanonicalLogging(listener = listener)
            var snap: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                ds.connection.use { conn ->
                    conn.createStatement().use { it.execute("SELECT pg_sleep(0)") }
                }
            }

            snap["db_query_count"] shouldBe 1L
            snap["db_execution_count"] shouldBe 1L
            snap["db_slow_execution_count"] shouldBe 1L
        }

        it("counts execution errors per round-trip, not per statement") {
            val ds = postgres.dataSource().withCanonicalLogging()
            var snap: Map<String, Any> = emptyMap()

            runCatching {
                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    ds.connection.use { conn ->
                        conn.createStatement().use { it.execute("SELECT * FROM nonexistent_table") }
                    }
                }
            }.exceptionOrNull().let { (it is SQLException) shouldBe true }

            snap["db_query_count"] shouldBe 1L
            snap["db_execution_count"] shouldBe 1L
            snap["db_execution_error_count"] shouldBe 1L
        }

        it("aggregates a JDBC batch as one execution but N queries") {
            val ds = postgres.dataSource().withCanonicalLogging()
            var snap: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                ds.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE TEMPORARY TABLE batch_test (n int)")
                    }
                    conn.createStatement().use { stmt ->
                        stmt.addBatch("INSERT INTO batch_test VALUES (1)")
                        stmt.addBatch("INSERT INTO batch_test VALUES (2)")
                        stmt.addBatch("INSERT INTO batch_test VALUES (3)")
                        stmt.executeBatch()
                    }
                }
            }

            // CREATE = 1 statement / 1 execution. The batch = 3 statements / 1 execution.
            snap["db_query_count"] shouldBe 4L
            snap["db_execution_count"] shouldBe 2L
        }

        it("each PreparedStatement execution fires afterQuery once") {
            val ds = postgres.dataSource().withCanonicalLogging()
            var snap: Map<String, Any> = emptyMap()

            withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                ds.connection.use { conn ->
                    conn.prepareStatement("SELECT ?").use { ps ->
                        ps.setInt(1, 1)
                        ps.executeQuery().close()
                        ps.setInt(1, 2)
                        ps.executeQuery().close()
                        ps.setInt(1, 3)
                        ps.executeQuery().close()
                    }
                }
            }

            // Re-using a PreparedStatement: each .executeQuery() is its own round-trip.
            snap["db_query_count"] shouldBe 3L
            snap["db_execution_count"] shouldBe 3L
        }

        it("is a no-op outside an active canonical context") {
            val ds = postgres.dataSource().withCanonicalLogging()
            ds.connection.use { conn ->
                conn.createStatement().use { stmt -> stmt.execute("SELECT 1") shouldBe true }
            }
        }
    }
})
