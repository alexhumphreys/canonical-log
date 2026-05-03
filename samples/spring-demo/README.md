# canonlog spring-demo

Self-contained Spring Boot 4 sample showing canonlog wired into HTTP, JDBC, and OkHttp.

## Run it

```sh
./gradlew :samples:spring-demo:bootRun
```

The app starts on port 8080 and seeds an H2 database with two posts. It also boots an in-process OkHttp `MockWebServer` so outbound HTTP calls have somewhere to land — no external dependencies.

## Try it

```sh
curl -s localhost:8080/posts/1 | jq
```

In the app's stdout you'll see one canonical log line per request, looking like:

```json
{
  "logger_name": "canonical",
  "message": "canonical",
  "http_request_method": "GET",
  "url_path": "/posts/1",
  "http_route": "/posts/{id}",
  "http_response_status_code": 200,
  "http_request_duration_ms": 21,
  "work_unit_id": "69eab700-480c-4611-af6d-6b7f4592e113",
  "work_unit_kind": "http",
  "db_query_count": 2,
  "db_execution_count": 2,
  "db_execution_duration_ms_total": 3,
  "http_client_request_count": 2,
  "http_client_request_duration_ms_total": 7,
  "post_id": 1,
  "tag_count": 3,
  "comment_count": 7,
  "cache_hit": false
}
```

Where each field comes from:

| Field | Source |
| --- | --- |
| `http_request_method`, `url_path`, `http_route`, `http_response_status_code`, `http_request_duration_ms` | `HttpWorkUnitAdapter` (umbrella starter) |
| `work_unit_id`, `work_unit_kind` | `HttpWorkUnitAdapter` |
| `db_query_count`, `db_execution_count`, `db_execution_duration_ms_total` | `JdbcCanonicalListener` (jdbc starter) |
| `http_client_request_count`, `http_client_request_duration_ms_total` | `OkHttpCanonicalInterceptor` (okhttp starter) |
| `post_id`, `tag_count`, `comment_count`, `cache_hit` | Handler code via `CanonicalLog.put` |

## Failure path

```sh
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/posts/999
```

Returns `404`, and the canonical line shows `error=true`, `error_reason=post_not_found`, `post_id=999` — the handler's `CanonicalLog.markFailed("post_not_found", "post_id" to id)` call survives unchanged through the adapter.
