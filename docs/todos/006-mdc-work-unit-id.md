# Put work_unit_id in MDC for log correlation

**Status:** todo · **Modules:** `canonical-log-core` and/or `canonical-log-spring-boot-starter`

## Problem

The canonical line is most useful when you can jump from it to the ordinary debug logs of the
same request. Nothing currently correlates them: the work unit's id is only on the canonical
line itself. Putting `work_unit_id` into slf4j MDC for the duration of the work unit makes
every app log line during the request carry the id.

## Design (sketch — decide details when implementing)

- Where to bind: the entry points (`withCanonicalLog{,Blocking}`, `CanonicalLogFilter`) set
  `MDC.put("work_unit_id", ctx.workUnit.id)` on install and restore the previous value on
  unwind (same previous-value discipline as the threadlocal).
- Coroutines: MDC is itself a threadlocal, so dispatcher switches lose it. Options:
  (a) fold MDC restore into `CanonicalLogElement.updateThreadContext/restoreThreadContext`
  (no new dependency, one element does both); (b) document pairing with
  `kotlinx-coroutines-slf4j`'s `MDCContext`. Option (a) is more in keeping with "the bridge is
  the only piece that knows about coroutine internals".
- Plain-thread hops: fold into `propagatingCanonicalContext()` wrappers.
- Should be opt-out-able (a filter/auto-config property, e.g.
  `canonical-log.http.mdc-enabled=true` default) since some shops already manage MDC.
- Key name: `work_unit_id` to match the canonical field.

## Acceptance

- Test: inside `withCanonicalLogBlocking`, `MDC.get("work_unit_id")` == work unit id; after,
  restored to previous; across `withContext(Dispatchers.IO)` inside `withCanonicalLog`, still
  present; cleared on the servlet thread after the filter returns (async path included).
- Sample app's `logback-spring.xml` shows the MDC field on ordinary lines; README note.
