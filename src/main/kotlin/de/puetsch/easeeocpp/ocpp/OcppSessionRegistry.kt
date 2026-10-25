package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.domain.model.ChargerId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** Pro ChargePointId höchstens eine aktive Sitzung; eine neue Verbindung ersetzt die alte. */
@Component
class OcppSessionRegistry {
    private val sessionsByChargePointId = ConcurrentHashMap<ChargerId, OcppChargerSession>()

    fun register(session: OcppChargerSession): OcppChargerSession? =
        sessionsByChargePointId.put(session.chargePointId, session)

    /** Gibt true zurück, wenn genau diese Sitzung noch registriert war; eine ersetzte Sitzung räumt nichts weg. */
    fun unregister(session: OcppChargerSession): Boolean =
        sessionsByChargePointId.remove(session.chargePointId, session)

    fun find(chargePointId: ChargerId): OcppChargerSession? = sessionsByChargePointId[chargePointId]

    fun isConnected(chargePointId: ChargerId): Boolean = find(chargePointId) != null
}
