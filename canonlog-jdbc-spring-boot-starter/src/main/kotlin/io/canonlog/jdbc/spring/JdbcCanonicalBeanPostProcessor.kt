package io.canonlog.jdbc.spring

import io.canonlog.jdbc.JdbcCanonicalListener
import io.canonlog.jdbc.withCanonicalLogging
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
 * **Concrete-type injection caveat.** Spring uses our return value as the bean
 * reference, so adopters who inject `@Autowired val ds: HikariDataSource` will
 * get a `BeanNotOfRequiredTypeException` after adding canonlog. Inject the
 * `DataSource` interface instead.
 */
public class JdbcCanonicalBeanPostProcessor : BeanPostProcessor, Ordered {
    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean !is DataSource) return bean
        if (bean is ProxyDataSource) {
            val chain = bean.proxyConfig.queryListener
            if (chain.listeners.none { it is JdbcCanonicalListener }) {
                chain.addListener(JdbcCanonicalListener())
            }
            return bean
        }
        return bean.withCanonicalLogging(name = beanName)
    }
}
