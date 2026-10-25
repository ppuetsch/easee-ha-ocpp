package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.domain.model.Ampere
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerSnapshot
import de.puetsch.easeeocpp.ports.ActuationResult
import de.puetsch.easeeocpp.ports.ChargerFleet
import org.springframework.stereotype.Component

@Component
class OcppChargerFleet(
    private val profiles: ChargingProfileService,
    private val tracker: ChargerStateTracker,
) : ChargerFleet {
    override fun snapshots(): List<ChargerSnapshot> = tracker.snapshots()

    override fun applyLimit(chargerId: ChargerId, current: Ampere): ActuationResult =
        profiles.applyLimit(chargerId, current)
}
