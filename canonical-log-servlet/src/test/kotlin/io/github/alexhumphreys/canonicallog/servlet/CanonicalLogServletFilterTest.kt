package io.github.alexhumphreys.canonicallog.servlet

import io.github.alexhumphreys.canonicallog.CanonicalLineWriter
import io.github.alexhumphreys.canonicallog.CanonicalLogContext
import io.github.alexhumphreys.canonicallog.currentCanonicalContext
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.slf4j.MDC

/** Test writer that keeps snapshots in memory instead of logging. */
private class CapturingLineWriter : CanonicalLineWriter {
    val snapshots: MutableList<Map<String, Any>> = mutableListOf()
    override fun write(context: CanonicalLogContext) {
        snapshots += context.snapshot()
    }
}

/**
 * Records what the *emitting thread* looks like at write time: the MDC `work_unit_id` and the
 * bound canonical context. The canonical line already carries `work_unit_id` as a field, so a
 * value still in MDC here is rendered a second time by MDC-including encoders (logstash's
 * `LogstashEncoder` does by default) — a duplicate JSON key.
 */
private class AmbientObservingLineWriter : CanonicalLineWriter {
    val mdcAtWrite: MutableList<String?> = mutableListOf()
    val contextAtWrite: MutableList<CanonicalLogContext?> = mutableListOf()
    override fun write(context: CanonicalLogContext) {
        mdcAtWrite += MDC.get("work_unit_id")
        contextAtWrite += currentCanonicalContext()
    }
}

class CanonicalLogServletFilterTest : DescribeSpec({

    describe("CanonicalLogServletFilter") {

        it("emits exactly one canonical line for a synchronous request") {
            val writer = CapturingLineWriter()
            val fake = FakeRequest("GET", "/sync", responseStatus = 200)
            CanonicalLogServletFilter(writer = writer)
                .doFilter(fake.request, fake.response, FilterChain { _, _ -> })

            writer.snapshots.size shouldBe 1
            val snap = writer.snapshots.single()
            snap["url_path"] shouldBe "/sync"
            snap["http_response_status_code"] shouldBe 200
        }

        it("emits an error line and rethrows when the handler throws") {
            val writer = CapturingLineWriter()
            val fake = FakeRequest("GET", "/boom", responseStatus = 500)

            val thrown = runCatching {
                CanonicalLogServletFilter(writer = writer)
                    .doFilter(fake.request, fake.response, FilterChain { _, _ -> error("kaboom") })
            }.exceptionOrNull()

            (thrown is IllegalStateException) shouldBe true
            writer.snapshots.size shouldBe 1
            val snap = writer.snapshots.single()
            snap["error"] shouldBe true
            snap["error_class"] shouldBe "java.lang.IllegalStateException"
        }

        it("defers emit until the async cycle completes for an async request") {
            val writer = CapturingLineWriter()
            val fake = FakeRequest("GET", "/async", asyncSupported = true, responseStatus = 200)

            val chain = FilterChain { _, _ -> fake.request.startAsync() }
            CanonicalLogServletFilter(writer = writer).doFilter(fake.request, fake.response, chain)

            // Filter returned, but the handler is still "running" async — no emit yet.
            writer.snapshots.size shouldBe 0

            // Simulate the container finishing the async cycle.
            fake.asyncContext!!.fireComplete()

            writer.snapshots.size shouldBe 1
            writer.snapshots.single()["url_path"] shouldBe "/async"
        }

        it("does not open a second work unit for a re-dispatch that is already filtered") {
            val writer = CapturingLineWriter()
            val filter = CanonicalLogServletFilter(writer = writer)
            val fake = FakeRequest("GET", "/forward", responseStatus = 200)

            var nestedChainRan = false
            // The outer chain re-enters the same filter (a FORWARD/ERROR-style re-dispatch)
            // while the once-per-request attribute is still set — the nested call must run
            // its chain straight through and open no second unit.
            val outerChain = FilterChain { req, res ->
                filter.doFilter(
                    req as jakarta.servlet.http.HttpServletRequest,
                    res as jakarta.servlet.http.HttpServletResponse,
                    FilterChain { _, _ -> nestedChainRan = true },
                )
            }
            filter.doFilter(fake.request, fake.response, outerChain)

            nestedChainRan shouldBe true
            // Exactly one canonical line despite the re-entry.
            writer.snapshots.size shouldBe 1
        }

        it("includeRequest=false runs the chain with no context bound and emits no line") {
            val writer = CapturingLineWriter()
            val fake = FakeRequest("GET", "/skip", responseStatus = 200)

            var chainRan = false
            var contextDuringChain: Any? = Any()
            CanonicalLogServletFilter(writer = writer, includeRequest = { false })
                .doFilter(fake.request, fake.response, FilterChain { _, _ ->
                    chainRan = true
                    contextDuringChain = currentCanonicalContext()
                })

            chainRan shouldBe true
            contextDuringChain shouldBe null
            writer.snapshots.size shouldBe 0
        }

        // Lifecycle reentrancy contract (todo 038): emit observes a finalized line with the unit
        // *not* bound. The sync and throwing paths used to emit before `scope.unbind()`, so the
        // canonical event carried `work_unit_id` in MDC as well as on the line itself.
        it("emits a synchronous request's line with the unit unbound (no work_unit_id in MDC)") {
            val writer = AmbientObservingLineWriter()
            val fake = FakeRequest("GET", "/sync", responseStatus = 200)
            CanonicalLogServletFilter(writer = writer)
                .doFilter(fake.request, fake.response, FilterChain { _, _ -> })

            writer.mdcAtWrite shouldBe listOf(null)
            writer.contextAtWrite shouldBe listOf(null)
        }

        it("emits a throwing request's line with the unit unbound (no work_unit_id in MDC)") {
            val writer = AmbientObservingLineWriter()
            val fake = FakeRequest("GET", "/boom", responseStatus = 500)
            runCatching {
                CanonicalLogServletFilter(writer = writer)
                    .doFilter(fake.request, fake.response, FilterChain { _, _ -> error("kaboom") })
            }

            writer.mdcAtWrite shouldBe listOf(null)
            writer.contextAtWrite shouldBe listOf(null)
        }

        it("emits with the enclosing request's binding, not its own, when nested under another unit") {
            val writer = AmbientObservingLineWriter()
            val outer = FakeRequest("GET", "/outer", responseStatus = 200)
            val inner = FakeRequest("GET", "/inner", responseStatus = 200)
            var outerId: String? = null
            // Distinct requests, so the once-per-request attribute guard does not skip the inner one.
            CanonicalLogServletFilter(writer = CapturingLineWriter())
                .doFilter(outer.request, outer.response, FilterChain { _, _ ->
                    outerId = currentCanonicalContext()?.workUnit?.id
                    CanonicalLogServletFilter(writer = writer)
                        .doFilter(inner.request, inner.response, FilterChain { _, _ -> })
                })

            (outerId == null) shouldBe false
            writer.mdcAtWrite shouldBe listOf(outerId)
            writer.contextAtWrite.single()?.workUnit?.id shouldBe outerId
        }

        it("unbinds the threadlocal after a synchronous request") {
            val fake = FakeRequest("GET", "/sync", responseStatus = 200)
            CanonicalLogServletFilter(writer = CapturingLineWriter())
                .doFilter(fake.request, fake.response, FilterChain { _, _ -> })
            currentCanonicalContext() shouldBe null
        }

        it("unbinds the threadlocal even when the handler throws") {
            val fake = FakeRequest("GET", "/boom", responseStatus = 500)
            runCatching {
                CanonicalLogServletFilter(writer = CapturingLineWriter())
                    .doFilter(fake.request, fake.response, FilterChain { _, _ -> error("kaboom") })
            }
            currentCanonicalContext() shouldBe null
        }

        it("unbinds the threadlocal on the request thread for an async request even though emit is deferred") {
            val fake = FakeRequest("GET", "/async", asyncSupported = true, responseStatus = 200)
            var contextDuringChain: Any? = null
            val chain = FilterChain { _, _ ->
                contextDuringChain = currentCanonicalContext()
                fake.request.startAsync()
            }
            CanonicalLogServletFilter(writer = CapturingLineWriter()).doFilter(fake.request, fake.response, chain)

            // Context was bound during the chain, but the servlet thread is clean on return
            // even though the work unit is still alive waiting for the async callback.
            (contextDuringChain == null) shouldBe false
            currentCanonicalContext() shouldBe null
        }
    }
})
