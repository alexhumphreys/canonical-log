package io.canonlog.jdbc.spring

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import javax.sql.DataSource

@AutoConfiguration
@ConditionalOnClass(DataSource::class)
@ConditionalOnProperty(
    name = ["canonlog.jdbc.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
public open class JdbcCanonicalAutoConfiguration {
    public companion object {
        @Bean
        @JvmStatic
        @ConditionalOnMissingBean
        public fun jdbcCanonicalBeanPostProcessor(): JdbcCanonicalBeanPostProcessor =
            JdbcCanonicalBeanPostProcessor()
    }
}
