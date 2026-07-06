package io.github.alexhumphreys.canonicallog.dropwizard

import io.dropwizard.core.Application
import io.dropwizard.core.Configuration
import io.dropwizard.core.setup.Bootstrap
import io.dropwizard.core.setup.Environment
import io.github.alexhumphreys.canonicallog.CanonicalLog
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.slf4j.LoggerFactory

/**
 * Minimal Dropwizard app that adds [CanonicalLogBundle] and one resource, used by
 * [CanonicalLogBundleTest] via `DropwizardTestSupport` (real in-process Jetty).
 *
 * `/ping` is excluded via the bundle's `excludePaths` to prove application-port exclusion.
 */
class TestApplication : Application<Configuration>() {

    override fun initialize(bootstrap: Bootstrap<Configuration>) {
        bootstrap.addBundle(CanonicalLogBundle(excludePaths = listOf("/ping")))
    }

    override fun run(configuration: Configuration, environment: Environment) {
        environment.jersey().register(PostsResource())
        environment.jersey().register(PingResource())
    }
}

/** Logger for the "ordinary handler log carries work_unit_id in MDC" assertion. */
private val handlerLogger = LoggerFactory.getLogger("test-handler")

@Path("/posts")
@Produces(MediaType.APPLICATION_JSON)
class PostsResource {

    @GET
    @Path("/{id}")
    fun get(@PathParam("id") id: String): Response {
        // Ordinary handler log line: during the request, CanonicalLogMdc has mirrored the
        // work_unit_id into MDC, so this event should carry it in its MDCPropertyMap.
        handlerLogger.info("handling post {}", id)
        return if (id == "999") {
            // markFailed 404 branch: a business failure with no exception — the line gets
            // error=true + error_reason=post_not_found but no error_class.
            CanonicalLog.markFailed("post_not_found")
            Response.status(Response.Status.NOT_FOUND).entity(mapOf("error" to "not_found")).build()
        } else {
            Response.ok(mapOf("id" to id)).build()
        }
    }

    @GET
    @Path("/{id}/boom")
    fun boom(@PathParam("id") id: String): Response {
        throw IllegalStateException("kaboom for post $id")
    }

    /**
     * Sub-resource **locator** (returns an instance, not a `Response`): this is what forces
     * a multi-segment `ExtendedUriInfo.getMatchedTemplates()` reconstruction, the ordering
     * pin. Route: `/posts/{id}/comments/{commentId}`.
     */
    @Path("/{id}/comments")
    fun comments(@PathParam("id") id: String): CommentsResource = CommentsResource()
}

class CommentsResource {
    @GET
    @Path("/{commentId}")
    @Produces(MediaType.APPLICATION_JSON)
    fun get(@PathParam("commentId") commentId: String): Map<String, String> = mapOf("commentId" to commentId)
}

@Path("/ping")
@Produces(MediaType.TEXT_PLAIN)
class PingResource {
    @GET
    fun ping(): String = "pong"
}
