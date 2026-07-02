package io.github.alexhumphreys.canonicallog.jdbc.spring

import io.github.alexhumphreys.canonicallog.jdbc.JdbcCanonicalListener
import io.github.alexhumphreys.canonicallog.jdbc.withCanonicalLogging
import net.ttddyy.dsproxy.support.ProxyDataSource
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.Ordered
import javax.sql.DataSource

/**
 * Wraps each [DataSource] bean with a [ProxyDataSource] that contributes JDBC
 * fields to the active canonical work unit.
 *
 * Runs at [Ordered.LOWEST_PRECEDENCE] so it executes after every other
 * [BeanPostProcessor] — the canonical bridge wraps the *outermost* proxy in the
 * stack. That means our `db_execution_duration_ms_total` includes whatever any
 * other proxy above us is doing (connection-pool waits, transaction boilerplate,
 * tracing overhead) — i.e. wall-clock time the application spent waiting on DB
 * work, which is what Stripe-style canonical lines are for.
 *
 * **Composition with other `datasource-proxy` users.** If the bean is already a
 * [ProxyDataSource] (because another library wrapped it first) and our listener
 * isn't already attached, we add ourselves to the existing chain instead of
 * wrapping again — `datasource-proxy` supports multiple listeners on one proxy.
 * If our listener is already attached (e.g. the adopter pre-wrapped the
 * `DataSource` themselves via [withCanonicalLogging]), we leave the bean
 * untouched.
 *
 * **Delegating `DataSource` beans are not double-instrumented.** An application
 * context often holds both an inner pool bean and an outer bean that delegates to
 * it (`LazyConnectionDataSourceProxy`, `TransactionAwareDataSourceProxy`, retry
 * wrappers). Since dependencies are post-processed first, the inner bean is
 * already canonical-log-wrapped by the time the outer bean is processed — wrapping
 * the outer too would double every `db_*` count. Before instrumenting, we walk the
 * delegation chain ([ProxyDataSource.getDataSource], plus a duck-typed
 * `getTargetDataSource()` for Spring's `DelegatingDataSource` family) and skip the
 * bean if a [JdbcCanonicalListener] is already attached anywhere beneath it. The
 * trade-off: for such stacks the instrumentation sits *below* the outer delegating
 * wrapper, so its overhead is not included in `db_execution_duration_ms_total`.
 * Known limitation: `AbstractRoutingDataSource` targets are not walked (they're a
 * map, not a single delegate) — a routing bean over target *beans* still
 * double-counts; exclude one side via `canonical-log.jdbc.enabled=false` or by
 * not exposing the targets as beans.
 *
 * **Concrete-type injection caveat.** Spring uses our return value as the bean
 * reference, so adopters who inject `@Autowired val ds: HikariDataSource` will
 * get a `BeanNotOfRequiredTypeException` after adding canonical-log. Inject the
 * `DataSource` interface instead.
 */
public class JdbcCanonicalBeanPostProcessor : BeanPostProcessor, Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean !is DataSource) return bean
        if (isAlreadyInstrumented(bean)) return bean
        if (bean is ProxyDataSource) {
            bean.proxyConfig.queryListener.addListener(JdbcCanonicalListener())
            return bean
        }
        return bean.withCanonicalLogging(name = beanName)
    }

    private fun isAlreadyInstrumented(root: DataSource): Boolean {
        var current: DataSource? = root
        var depth = 0
        while (current != null && depth++ < MAX_DELEGATION_DEPTH) {
            if (current is ProxyDataSource &&
                current.proxyConfig.queryListener.listeners.any { it is JdbcCanonicalListener }
            ) {
                return true
            }
            current = delegateOf(current)
        }
        return false
    }

    private fun delegateOf(ds: DataSource): DataSource? =
        if (ds is ProxyDataSource) {
            ds.dataSource
        } else {
            // Duck-typed for Spring's DelegatingDataSource family without a
            // spring-jdbc dependency: any wrapper exposing a public
            // getTargetDataSource() is followed.
            runCatching {
                ds.javaClass.getMethod("getTargetDataSource").invoke(ds) as? DataSource
            }.getOrNull()
        }

    private companion object {
        // Defensive bound: delegation chains are short in practice; this only guards
        // against a pathological self-referencing wrapper looping the walk forever.
        const val MAX_DELEGATION_DEPTH = 20
    }
}
