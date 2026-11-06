package io.github.alexhumphreys.canonicallog.jdbc

import io.github.alexhumphreys.canonicallog.CanonicalFields
import io.github.alexhumphreys.canonicallog.test.contributorContract
import io.github.alexhumphreys.canonicallog.test.failingScenario
import io.github.alexhumphreys.canonicallog.test.scenario
import io.kotest.core.spec.style.DescribeSpec
import org.h2.jdbcx.JdbcDataSource
import java.sql.SQLException
import javax.sql.DataSource

/**
 * The shared contributor contract (todo 048) for [JdbcCanonicalListener], driven through a real
 * proxied in-memory H2 [DataSource] — the same `withCanonicalLogging()` wiring adopters use,
 * without the Postgres container [JdbcCanonicalListenerTest] boots for dialect-level coverage.
 */
class JdbcContributorContractTest : DescribeSpec({

    fun h2(): DataSource = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:contract;DB_CLOSE_DELAY=-1")
    }

    fun DataSource.execute(sql: String) {
        connection.use { conn -> conn.createStatement().use { it.execute(sql) } }
    }

    // Created through the raw DataSource so the DDL contributes to no line.
    h2().execute("CREATE TABLE IF NOT EXISTS contract_rows (id INT)")
    val ds = h2().withCanonicalLogging()
    val slowDs = h2().withCanonicalLogging(listener = JdbcCanonicalListener(slowQueryThresholdMs = 0L))

    val executionFields = mapOf(
        CanonicalFields.DB_QUERY_COUNT to Long::class,
        CanonicalFields.DB_EXECUTION_COUNT to Long::class,
        CanonicalFields.DB_EXECUTION_DURATION_MS_TOTAL to Long::class,
    )

    describe("JdbcCanonicalListener contributor contract") {
        contributorContract(
            name = "jdbc listener",
            operation = { ds.execute("SELECT 1") },
            expectedFields = executionFields,
            allowedFieldPrefixes = setOf("db_"),
            failure = failingScenario<SQLException>(
                executionFields + (CanonicalFields.DB_EXECUTION_ERROR_COUNT to Long::class),
            ) {
                ds.execute("SELECT FROM WHERE")
            },
            adversarial = mapOf(
                "a batch of statements" to scenario(executionFields) {
                    ds.connection.use { conn ->
                        conn.createStatement().use { st ->
                            repeat(3) { i -> st.addBatch("INSERT INTO contract_rows VALUES ($i)") }
                            st.executeBatch()
                        }
                    }
                },
                "an empty batch" to scenario {
                    ds.connection.use { conn -> conn.createStatement().use { it.executeBatch() } }
                },
                "a slow execution at a zero threshold" to scenario(
                    executionFields + (CanonicalFields.DB_SLOW_EXECUTION_COUNT to Long::class),
                ) {
                    slowDs.execute("SELECT 1")
                },
                "a hostile bound parameter" to scenario(executionFields) {
                    ds.connection.use { conn ->
                        conn.prepareStatement("SELECT ?").use { ps ->
                            ps.setString(1, "'; DROP TABLE users; --" + "x".repeat(10_000))
                            ps.executeQuery().close()
                        }
                    }
                },
            ),
        ).forEach { case -> it(case.name) { case.run() } }
    }
})
