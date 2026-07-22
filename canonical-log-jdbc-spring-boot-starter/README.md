# canonical-log-jdbc-spring-boot-starter

Auto-configures the [JDBC contributor](../canonical-log-jdbc/README.md): a
`BeanPostProcessor` wraps every `DataSource` bean in a `datasource-proxy` that contributes
`db_*` fields ([docs/fields.md](../docs/fields.md)) to the active work unit. No code changes
needed — pulled in transitively by the umbrella starter.

Opt out with `canonical-log.jdbc.enabled=false`.

## Gotchas

- The BPP replaces `DataSource` beans with proxies, so **inject `DataSource`, not a concrete
  type** — `@Autowired val ds: HikariDataSource` fails with `BeanNotOfRequiredTypeException`.
- It runs at `LOWEST_PRECEDENCE` to wrap the outermost proxy. Another `datasource-proxy`
  user on the classpath is joined (not re-wrapped); a non-`datasource-proxy` tracing wrapper
  above us gets proxied, so its overhead lands in `db_execution_duration_ms_total`.
- Already-wrapped delegation chains are detected and skipped (no double-counting), but
  `AbstractRoutingDataSource` target maps are not walked — a router bean over target beans
  double-counts.
- Multiple `DataSource` beans all write to the same accumulator: `db_query_count` is the
  sum across primary + replica. There is no per-datasource namespacing today.
