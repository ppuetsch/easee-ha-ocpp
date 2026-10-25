package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.ocpp.messages.ChangeConfigurationRequest
import de.puetsch.easeeocpp.ocpp.messages.GetConfigurationRequest
import de.puetsch.easeeocpp.ocpp.messages.OcppConfigurationKeys
import de.puetsch.easeeocpp.ocpp.messages.OcppRequestedMessages
import de.puetsch.easeeocpp.ocpp.messages.TriggerMessageRequest
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val METER_VALUES_SAMPLED_DATA_VALUE =
    "Power.Active.Import,Current.Import,Voltage,Current.Offered,Energy.Active.Import.Register"

// Easee lehnt kurze Messintervalle ab; die Folge probiert vom gewünschten Wert aufwärts, bis der Charger zustimmt.
private val SAMPLE_INTERVAL_FALLBACK_SECONDS = listOf(10L, 15L, 30L, 60L)

/**
 * Konfiguriert den Charger nach einer neuen Verbindung: Messwerte, Telemetrie-Trigger, dann das Regelprofil.
 * Kostenloses Laden (`FreeCharging`) wird bewusst nie gesetzt: Easee kennt die Keys nicht, und die Ladefreigabe
 * läuft über die RFID-Whitelist.
 */
@Component
class ChargerBootstrapper(
    private val sessions: OcppSessionRegistry,
    private val profiles: ChargingProfileService,
    private val settings: AppProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val bootstrappedSessionByChargerId = ConcurrentHashMap<ChargerId, OcppChargerSession>()

    /** Startet die Konfigurationsfolge höchstens einmal pro Sitzung. Liefert false, wenn sie für diese Sitzung schon gestartet wurde. */
    @Synchronized
    fun bootstrapOnceAsync(chargerId: ChargerId): Boolean {
        val session = sessions.find(chargerId) ?: return false
        if (bootstrappedSessionByChargerId[chargerId] === session) return false
        bootstrappedSessionByChargerId[chargerId] = session
        executor.execute { bootstrap(chargerId) }
        return true
    }

    fun bootstrap(chargerId: ChargerId) {
        val session = sessions.find(chargerId) ?: return
        logger.info("Bootstrap von {} gestartet", chargerId)
        configurationChanges().forEach { change -> applyConfigurationChange(chargerId, session, change) }
        negotiateSampleInterval(chargerId, session)
        logCurrentConfiguration(chargerId, session)
        requestTelemetryMessages(chargerId, session)
        resendDesiredLimit(chargerId)
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
    )

    private fun negotiateSampleInterval(chargerId: ChargerId, session: OcppChargerSession) {
        val wanted = settings.ocpp.meterValueSampleInterval.seconds
        val candidates = listOf(wanted) + SAMPLE_INTERVAL_FALLBACK_SECONDS.filter { it > wanted }
        val accepted = candidates.firstOrNull { seconds ->
            val change = ChangeConfigurationRequest(OcppConfigurationKeys.METER_VALUE_SAMPLE_INTERVAL, seconds.toString())
            session.call(OcppActions.CHANGE_CONFIGURATION, change).join().isAccepted()
        }
        if (accepted == null) logger.warn("{} akzeptiert kein MeterValueSampleInterval aus {}", chargerId, candidates)
        else logger.info("{}: MeterValueSampleInterval {} s", chargerId, accepted)
    }

    private fun logCurrentConfiguration(chargerId: ChargerId, session: OcppChargerSession) {
        val response = session.call(OcppActions.GET_CONFIGURATION, GetConfigurationRequest()).join()
        logger.info("Konfiguration von {}: {}", chargerId, (response as? OcppResponse.Result)?.payload?.toString() ?: response.describe())
    }

    private fun applyConfigurationChange(chargerId: ChargerId, session: OcppChargerSession, change: ChangeConfigurationRequest) {
        val response = session.call(OcppActions.CHANGE_CONFIGURATION, change).join()
        logIfFailed(chargerId, "ChangeConfiguration ${change.key}", response.describe(), response.isAccepted())
    }

    private fun requestTelemetryMessages(chargerId: ChargerId, session: OcppChargerSession) {
        triggerMessage(
            chargerId,
            session,
            TriggerMessageRequest(OcppRequestedMessages.STATUS_NOTIFICATION, connectorId = CONNECTOR_ID_VEHICLE),
        )
        triggerMessage(chargerId, session, TriggerMessageRequest(OcppRequestedMessages.METER_VALUES))
    }

    private fun triggerMessage(chargerId: ChargerId, session: OcppChargerSession, request: TriggerMessageRequest) {
        val response = session.call(OcppActions.TRIGGER_MESSAGE, request).join()
        logIfFailed(chargerId, "TriggerMessage ${request.requestedMessage}", response.describe(), response.isAccepted())
    }

    private fun resendDesiredLimit(chargerId: ChargerId) {
        val result = profiles.resendDesiredLimit(chargerId) ?: return
        logIfFailed(chargerId, "Limit nach Boot", result.message, result.success)
    }

    private fun logIfFailed(chargerId: ChargerId, step: String, detail: String, success: Boolean) {
        if (!success) logger.warn("{} für {} fehlgeschlagen: {}", step, chargerId, detail)
    }
}
