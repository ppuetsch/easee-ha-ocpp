package de.puetsch.easeeocpp.server

import de.puetsch.easeeocpp.config.AppProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Läuft vor dem DispatcherServlet und entscheidet anhand des Connector-Ports, ob ein Pfad erreichbar ist.
 * Der Pfad kommt aus dem Servlet-Mapping, nicht aus der Roh-URI, damit `/ocpp/../actuator/health` nicht
 * über den OCPP-Port nach außen gelangt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class OcppPortPathFilter(settings: AppProperties) : OncePerRequestFilter() {
    private val policy = OcppPortPolicy(settings.ocpp.port, settings.ocpp.path)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (policy.permits(request.localPort, routedPath(request))) {
            filterChain.doFilter(request, response)
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
        }
    }

    private fun routedPath(request: HttpServletRequest): String =
        request.servletPath + request.pathInfo.orEmpty()
}
