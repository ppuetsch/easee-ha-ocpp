package de.puetsch.easeeocpp.server

/**
 * Trennt die Ports fachlich: Der OCPP-Port liefert ausschließlich OCPP-Pfade, alle übrigen Ports (GUI, Actuator)
 * liefern keine OCPP-Pfade. Damit ist ein Charger-Zugang über den GUI-Port nicht möglich.
 */
class OcppPortPolicy(
    private val ocppPort: Int,
    ocppPath: String,
) {
    private val ocppPathPrefix: String = ocppPath.trimEnd('/') + PATH_SEPARATOR

    fun permits(localPort: Int, routedPath: String): Boolean {
        val isOcppRequest = routedPath.startsWith(ocppPathPrefix)
        return if (localPort == ocppPort) isOcppRequest else !isOcppRequest
    }

    private companion object {
        const val PATH_SEPARATOR = "/"
    }
}
