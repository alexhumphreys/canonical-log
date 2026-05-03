package io.canonlog.jdbc

import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder
import javax.sql.DataSource

public fun DataSource.withCanonicalLogging(
    name: String = "canonlog-jdbc",
    listener: JdbcCanonicalListener = JdbcCanonicalListener(),
): DataSource =
    ProxyDataSourceBuilder.create(this)
        .name(name)
        .listener(listener)
        .build()
