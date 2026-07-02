# Human-readable message on the canonical line

**Status:** todo · **Modules:** `canonical-log-spring-boot-starter`
**Depends on:** 001 (message formatting belongs to the `CanonicalLineWriter`)

## Problem

The emitted log event's message is the literal string `"canonical"`. In a plain console
(local dev, `kubectl logs` without a JSON viewer) the line is unreadable without mentally
parsing JSON. Stripe-style lines are also greppable text: `GET /posts/{id} 200 12ms` as the
message makes the line skimmable while all fields stay structured.

## Design (sketch)

- In `LogstashCanonicalLineWriter` (from 001): build the message from well-known fields when
  present — `"$http_request_method ${http_route ?: url_path} $http_response_status_code ${http_request_duration_ms}ms"`,
  falling back to `work_unit_kind` + `work_unit_id` for non-HTTP units. Append an error hint
  (`error_reason`) when `error=true`.
- Keep it in the writer, not the adapter — it's presentation, and custom writers may want
  their own format. Consider exposing the formatter as a constructor param
  (`message: (Map<String, Any>) -> String`).
- Mind duplication: JSON output will carry the fields twice (message + structured). Accepted —
  the message is for humans. Note for query authors: never parse the message.

## Acceptance

Filter/writer test asserting the composed message for a sync request and for a non-HTTP work
unit; sample app README example lines updated (they show real output).
