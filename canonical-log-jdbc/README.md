# canonical-log-jdbc

Framework-agnostic JDBC contributor: a
[`datasource-proxy`](https://github.com/jdbc-observations/datasource-proxy)
`QueryExecutionListener` (`JdbcCanonicalListener`) contributing `db_query_count`,
`db_execution_count`, `db_execution_duration_ms_total`, `db_slow_execution_count`,
`db_execution_error_count` to the active work unit ([docs/fields.md](../docs/fields.md)).

```kotlin
val proxied = ProxyDataSourceBuilder.create(dataSource)
    .listener(JdbcCanonicalListener())
    .build()
```

Spring Boot users: the [starter](../canonical-log-jdbc-spring-boot-starter/README.md) wraps
your `DataSource` beans automatically.

## Reading the numbers

- `db_query_count` counts statements (a batch of N counts as N); `db_execution_count`
  counts round-trips (a batch counts as 1).
- `db_execution_duration_ms_total / db_execution_count` is mean per-round-trip latency.
  Don't divide by `db_query_count` — duration is charged once per execution, so that
  under-reports batched statements.
