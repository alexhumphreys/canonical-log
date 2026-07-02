package io.github.alexhumphreys.canonicallog.jdbc.spring

import com.zaxxer.hikari.HikariDataSource
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.Outcome
import io.github.alexhumphreys.canonicallog.WorkUnit
import io.github.alexhumphreys.canonicallog.WorkUnitAdapter
import io.github.alexhumphreys.canonicallog.jdbc.JdbcCanonicalListener
import io.github.alexhumphreys.canonicallog.jdbc.withCanonicalLogging
import io.github.alexhumphreys.canonicallog.withCanonicalLogBlocking
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import net.ttddyy.dsproxy.support.ProxyDataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.Ordered
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource

private val nullAdapter = object : WorkUnitAdapter<String> {
    override fun describe(input: String): WorkUnit = WorkUnit(input, "test", Instant.now())
    override fun enrich(ctx: CanonicalLogContext, input: String, outcome: Outcome) {}
}

class JdbcCanonicalAutoConfigurationTest : DescribeSpec({

    val postgres = PostgreSQLContainer("postgres:16-alpine")

    beforeSpec { postgres.start() }
    afterSpec { postgres.stop() }

    fun runner(): ApplicationContextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JdbcCanonicalAutoConfiguration::class.java,
                DataSourceAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "spring.datasource.url=${postgres.jdbcUrl}",
            "spring.datasource.username=${postgres.username}",
            "spring.datasource.password=${postgres.password}",
            "spring.datasource.driver-class-name=org.postgresql.Driver",
        )

    describe("JdbcCanonicalAutoConfiguration") {

        it("BPP runs at LOWEST_PRECEDENCE so it wraps the outermost proxy in the stack") {
            JdbcCanonicalBeanPostProcessor().order shouldBe Ordered.LOWEST_PRECEDENCE
        }

        it("wraps Spring Boot's auto-configured (HikariCP) DataSource") {
            runner().run { ctx ->
                val ds = ctx.getBean(DataSource::class.java)
                check(ds is ProxyDataSource) { "expected ProxyDataSource, got ${ds::class.qualifiedName}" }
                // Important layering check: the underlying DataSource is HikariCP, not the
                // raw Postgres driver. That means our proxy sits *above* the connection
                // pool, so duration measurements include pool wait time — which is what
                // the application actually experiences.
                ds.dataSource.shouldBeInstanceOfNamed("com.zaxxer.hikari.HikariDataSource")
            }
        }

        it("the wrapped DataSource emits canonical fields when used") {
            runner().run { ctx ->
                val ds = ctx.getBean(DataSource::class.java)
                var snap: Map<String, Any> = emptyMap()
                withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                    ds.connection.use { c ->
                        c.createStatement().use { it.execute("SELECT 1") }
                        c.createStatement().use { it.execute("SELECT 2") }
                    }
                }
                snap["db_query_count"] shouldBe 2L
                snap["db_execution_count"] shouldBe 2L
            }
        }

        it("does not double-wrap a DataSource that's already canonical-log-wrapped") {
            // Pre-wrap: this is what an adopter who reaches for withCanonicalLogging
            // directly would have. The BPP should leave this bean untouched.
            val original: DataSource = hikariDataSource(postgres).withCanonicalLogging(name = "pre-wrapped")

            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcCanonicalAutoConfiguration::class.java))
                .withBean("dataSource", DataSource::class.java, { original })
                .run { ctx ->
                    // Same instance — the BPP recognised that our listener is already on
                    // the chain and returned the bean unchanged. (The previous version of
                    // this test only checked the proxy's name, which would still pass even
                    // if the BPP had re-wrapped or no-op'd entirely.)
                    val ds = ctx.getBean(DataSource::class.java)
                    ds shouldBeSameInstanceAs original
                }
        }

        it("composes with another datasource-proxy user: adds our listener to the existing chain") {
            // Simulate another library having already proxied the datasource without
            // canonical-log. Our BPP should add itself to the existing chain rather than
            // re-wrap (which would create proxy-of-proxy and break our listener
            // detection in the future).
            val externalListener = object : net.ttddyy.dsproxy.listener.QueryExecutionListener {
                override fun beforeQuery(
                    e: net.ttddyy.dsproxy.ExecutionInfo,
                    q: MutableList<net.ttddyy.dsproxy.QueryInfo>,
                ) = Unit
                override fun afterQuery(
                    e: net.ttddyy.dsproxy.ExecutionInfo,
                    q: MutableList<net.ttddyy.dsproxy.QueryInfo>,
                ) = Unit
            }
            val foreignProxy: DataSource =
                net.ttddyy.dsproxy.support.ProxyDataSourceBuilder.create(hikariDataSource(postgres))
                    .name("foreign-proxy")
                    .listener(externalListener)
                    .build()

            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcCanonicalAutoConfiguration::class.java))
                .withBean("dataSource", DataSource::class.java, { foreignProxy })
                .run { ctx ->
                    val ds = ctx.getBean(DataSource::class.java)
                    ds shouldBeSameInstanceAs foreignProxy

                    val proxy = ds as ProxyDataSource
                    val listeners = proxy.proxyConfig.queryListener.listeners
                    listeners.any { it === externalListener } shouldBe true
                    listeners.any { it is JdbcCanonicalListener } shouldBe true

                    // Functional check: queries through the composed proxy still emit
                    // canonical fields.
                    var snap: Map<String, Any> = emptyMap()
                    withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                        ds.connection.use { c ->
                            c.createStatement().use { it.execute("SELECT 1") }
                        }
                    }
                    snap["db_query_count"] shouldBe 1L
                }
        }

        it("does not double-instrument through a delegating DataSource bean") {
            // Common production shape: an inner pool bean plus an outer bean that
            // delegates to it (LazyConnectionDataSourceProxy, TransactionAware...,
            // retry wrappers). The inner bean is post-processed (and wrapped) first;
            // wrapping the outer as well would double every db_* count.
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JdbcCanonicalAutoConfiguration::class.java))
                .withBean("innerDataSource", DataSource::class.java, { hikariDataSource(postgres) })
                .withUserConfiguration(DelegatingOuterConfig::class.java)
                .run { ctx ->
                    // Inner got wrapped; outer was left untouched because our listener
                    // is already reachable down its delegation chain.
                    (ctx.getBean("innerDataSource") is ProxyDataSource) shouldBe true
                    val outer = ctx.getBean("outerDataSource", DataSource::class.java)
                    (outer is ProxyDataSource) shouldBe false

                    var snap: Map<String, Any> = emptyMap()
                    withCanonicalLogBlocking(nullAdapter, "wu", { snap = it.snapshot() }) {
                        outer.connection.use { c ->
                            c.createStatement().use { it.execute("SELECT 1") }
                        }
                    }
                    snap["db_query_count"] shouldBe 1L
                    snap["db_execution_count"] shouldBe 1L
                }
        }

        it("opts out when canonical-log.jdbc.enabled=false") {
            runner().withPropertyValues("canonical-log.jdbc.enabled=false").run { ctx ->
                ctx.containsBean("jdbcCanonicalBeanPostProcessor") shouldBe false
                // The auto-configured DataSource is not wrapped by us.
                val ds = ctx.getBean(DataSource::class.java)
                (ds is ProxyDataSource) shouldBe false
            }
        }
    }
})

@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
private class DelegatingOuterConfig {
    @org.springframework.context.annotation.Bean
    fun outerDataSource(
        @org.springframework.beans.factory.annotation.Qualifier("innerDataSource") inner: DataSource,
    ): DataSource = org.springframework.jdbc.datasource.DelegatingDataSource(inner)
}

private fun hikariDataSource(postgres: PostgreSQLContainer<*>): HikariDataSource = HikariDataSource().apply {
    jdbcUrl = postgres.jdbcUrl
    username = postgres.username
    password = postgres.password
    driverClassName = "org.postgresql.Driver"
    maximumPoolSize = 2
}

private fun Any.shouldBeInstanceOfNamed(qualifiedName: String) {
    this::class.qualifiedName shouldBe qualifiedName
}
