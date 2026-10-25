package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.ocpp.messages.ChangeConfigurationRequest
import de.puetsch.easeeocpp.ocpp.messages.OcppConfigurationKeys
import de.puetsch.easeeocpp.ocpp.messages.OcppRequestedMessages
import de.puetsch.easeeocpp.ocpp.messages.TriggerMessageRequest
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val METER_VALUES_SAMPLED_DATA_VALUE =
    "Power.Active.Import,Current.Import,Voltage,Current.Offered,Energy.Active.Import.Register"
private const val FREE_CHARGING_ENABLED = "true"

/** Konfiguriert den Charger nach BootNotification: Messwerte, kostenloses Laden ohne Autorisierung, dann Messwert-Trigger. */
@Component
class ChargerBootstrapper(
    private val sessions: OcppSessionRegistry,
    private val profiles: ChargingProfileService,
    private val settings: AppProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    fun bootstrapAsync(chargerId: ChargerId) {
        executor.execute { bootstrap(chargerId) }
    }

    fun bootstrap(chargerId: ChargerId) {
        val session = sessions.find(chargerId) ?: return
        logger.info("Bootstrap von {} gestartet", chargerId)
        configurationChanges().forEach { change -> applyConfigurationChange(chargerId, session, change) }
        requestMeterValues(chargerId, session)
        profiles.resendDesiredLimit(chargerId)?.let { result -> logIfFailed(chargerId, "Limit nach Boot", result.message, result.success) }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    private fun configurationChanges(): List<ChangeConfigurationRequest> = listOf(
        ChangeConfigurationRequest(
            OcppConfigurationKeys.METER_VALUES_SAMPLED_DATA,
            METER_VALUES_SAMPLED_DATA_VALUE,
        ),
        ChangeConfigurationRequest(
            OcppConfigurationKeys.METER_VALUE_SAMPLE_INTERVAL,
            settings.ocpp.meterValueSampleInterval.seconds.toString(),
        ),
        ChangeConfigurationRequest(OcppConfigurationKeys.FREE_CHARGING, FREE_CHARGING_ENABLED),
        ChangeConfigurationRequest(OcppConfigurationKeys.FREE_CHARGING_ID_TAG, settings.ocpp.freeChargingIdTag),
    )

    private fun applyConfigurationChange(chargerId: ChargerId, session: OcppChargerSession, change: ChangeConfigurationRequest) {
        val response = session.call(OcppActions.CHANGE_CONFIGURATION, change).join()
        logIfFailed(chargerId, "ChangeConfiguration ${change.key}", response.describe(), response.isAccepted())
    }

    private fun requestMeterValues(chargerId: ChargerId, session: OcppChargerSession) {
        val response = session.call(
            OcppActions.TRIGGER_MESSAGE,
            TriggerMessageRequest(requestedMessage = OcppRequestedMessages.METER_VALUES),
        ).join()
        logIfFailed(chargerId, "TriggerMessage MeterValues", response.describe(), response.isAccepted())
    }

    private fun logIfFailed(chargerId: ChargerId, step: String, detail: String, success: Boolean) {
        if (!success) logger.warn("{} für {} fehlgeschlagen: {}", step, chargerId, detail)
    }
}
