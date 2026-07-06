package io.github.alexhumphreys.canonicallog.dropwizard

import io.github.alexhumphreys.canonicallog.servlet.HttpWorkUnitAdapter
import jakarta.inject.Inject
import jakarta.servlet.http.HttpServletRequest
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.ext.Provider
import org.glassfish.jersey.server.ExtendedUriInfo
import org.slf4j.LoggerFactory

/**
 * Same logger name core and the servlet lifecycle use for their own failure reporting, so
 * adopters configure one logger for all canonical-log library warnings.
 */
private val libraryLogger = LoggerFactory.getLogger("io.github.alexhumphreys.canonicallog")

/**
 * A post-matching Jersey [ContainerRequestFilter] whose only job is to publish the matched
 * route template (`/posts/{id}`) to the servlet layer, where [HttpWorkUnitAdapter]'s default
 * `routeResolver` reads it back as `http_route`.
 *
 * Deliberately **not** `@PreMatching`: it must run *after* resource matching so
 * [ExtendedUriInfo.getMatchedTemplates] is populated. Any post-matching priority is fine —
 * the filter reads state Jersey has already computed and touches nothing the handler needs.
 *
 * Jersey returns the matched templates **most-specific-first**, so the full route is the
 * reversed join of every template segment, with duplicate slashes normalized (`//` → `/`)
 * where a sub-resource locator's leading slash meets a parent's trailing slash. This ordering
 * is pinned empirically by a sub-resource case in `CanonicalLogBundleTest`.
 *
 * The whole body is wrapped in a catch-all: route capture is telemetry, so a non-servlet
 * deployment, a missing [ExtendedUriInfo], or an injection failure must never 500 a request —
 * it simply logs a WARN and leaves `http_route` off that line. A request that never reaches
 * matching (a 404 before routing) has no matched templates and therefore no attribute, so
 * `http_route` is omitted — the same behaviour as the Spring starter.
 *
 * Registered automatically by [CanonicalLogBundle]; it need not be referenced directly.
 */
@Provider
public class JerseyRouteCaptureFilter @Inject constructor(
    private val servletRequest: jakarta.inject.Provider<HttpServletRequest>,
) : ContainerRequestFilter {

    override fun filter(requestContext: ContainerRequestContext) {
        try {
            val uriInfo = requestContext.uriInfo as? ExtendedUriInfo ?: return
            // matchedTemplates is most-specific-first; reverse to read parent → child and
            // join into the full template. Normalize any duplicate slash created where a
            // sub-resource locator's leading "/" meets a parent template's trailing "/".
            val route = uriInfo.matchedTemplates
                .reversed()
                .joinToString("") { it.template }
                .replace("//", "/")
            servletRequest.get().setAttribute(HttpWorkUnitAdapter.ROUTE_ATTRIBUTE, route)
        } catch (e: Exception) {
            libraryLogger.warn(
                "canonical-log Jersey route capture failed; http_route will be omitted for this request",
                e,
            )
        }
    }
}
