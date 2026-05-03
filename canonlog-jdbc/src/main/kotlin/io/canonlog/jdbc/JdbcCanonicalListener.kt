package io.canonlog.jdbc

import io.canonlog.CanonicalLog
import net.ttddyy.dsproxy.ExecutionInfo
import net.ttddyy.dsproxy.QueryInfo
import net.ttddyy.dsproxy.listener.QueryExecutionListener

/**
 * Contributes JDBC fields to the active canonical work unit.
 *
 * Fields:
 *
 * - `db_query_count` — total **statement** count. A JDBC batch of N statements
 *   counts as N. Use this to answer "how much SQL did this request issue?".
 * - `db_execution_count` — total **execution** count. A JDBC batch counts as 1,
 *   because it's one network round-trip. Use this to answer "how many DB
 *   round-trips did this request make?".
 * - `db_execution_duration_ms_total` — total wall-clock time spent on executions,
 *   including failed ones. Charged once per `afterQuery` callback. Pair with
 *   `db_execution_count` to compute mean per-round-trip latency.
 * - `db_slow_execution_count` — number of executions whose elapsed time exceeded
 *   [slowQueryThresholdMs]. Per-execution, not per-statement, because
 *   `datasource-proxy` does not surface per-statement timing inside a batch.
 * - `db_execution_error_count` — number of failed executions (1 per failed
 *   `afterQuery`). Per-execution, not per-statement, for the same reason.
 *
 * **The execution/query split is deliberate.** Aggregating a batch as N queries but
 * charging duration once would make `db_query_duration_ms_total / db_query_count`
 * meaningless for batched workloads. Splitting the fields lets operators ask
 * either question honestly. Most request handlers don't batch, so for them
 * `db_execution_count == db_query_count` and the split is invisible.
 */
public class JdbcCanonicalListener(
    private val slowQueryThresholdMs: Long = DEFAULT_SLOW_QUERY_THRESHOLD_MS,
) : QueryExecutionListener {

    override fun beforeQuery(execInfo: ExecutionInfo, queryInfoList: MutableList<QueryInfo>) {
        // no-op
    }

    override fun afterQuery(execInfo: ExecutionInfo, queryInfoList: MutableList<QueryInfo>) {
        CanonicalLog.increment("db_query_count", queryInfoList.size.toLong())
        CanonicalLog.increment("db_execution_count", 1L)
        // Time is charged for failed executions too — operators want total time-spent
        // on DB work, not just successful work.
        CanonicalLog.increment("db_execution_duration_ms_total", execInfo.elapsedTime)
        if (execInfo.elapsedTime > slowQueryThresholdMs) {
            CanonicalLog.increment("db_slow_execution_count")
        }
        if (!execInfo.isSuccess) {
            CanonicalLog.increment("db_execution_error_count")
        }
    }

    public companion object {
        public const val DEFAULT_SLOW_QUERY_THRESHOLD_MS: Long = 100L
    }
}
