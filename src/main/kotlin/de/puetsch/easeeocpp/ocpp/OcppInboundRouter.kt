package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import de.puetsch.easeeocpp.domain.model.ChargerId
import de.puetsch.easeeocpp.domain.model.ChargerStatus
import de.puetsch.easeeocpp.ocpp.messages.AuthorizeRequest
import de.puetsch.easeeocpp.ocpp.messages.AuthorizeResponse
import de.puetsch.easeeocpp.ocpp.messages.BootNotificationRequest
import de.puetsch.easeeocpp.ocpp.messages.BootNotificationResponse
import de.puetsch.easeeocpp.ocpp.messages.HeartbeatResponse
import de.puetsch.easeeocpp.ocpp.messages.IdTagInfo
import de.puetsch.easeeocpp.ocpp.messages.MeterValuesRequest
import de.puetsch.easeeocpp.ocpp.messages.StartTransactionRequest
import de.puetsch.easeeocpp.ocpp.messages.StartTransactionResponse
import de.puetsch.easeeocpp.ocpp.messages.StatusNotificationRequest
import de.puetsch.easeeocpp.ocpp.messages.StopTransactionRequest
import de.puetsch.easeeocpp.ocpp.messages.StopTransactionResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

/**
 * OCPP-Transaktionsnummern sind positive 32-Bit-Ganzzahlen. Der Zähler startet aus der Zeit,
 * damit er nach einem Neustart nicht wieder bei 1 beginnt.
 */
private const val MAX_TRANSACTION_ID = Int.MAX_VALUE

/** Beantwortet eingehende CALLs des Chargers. Zustandsänderungen landen im [ChargerStateTracker]. */
@Component
class OcppInboundRouter(
    private val tracker: ChargerStateTracker,
    private val clock: Clock,
    private val settings: AppProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val nextTransactionId = AtomicInteger(transactionIdSeed())
    private val handlersByAction: Map<String, (ChargerId, JsonNode) -> JsonNode> = mapOf(
        OcppActions.BOOT_NOTIFICATION to ::handleBootNotification,
        OcppActions.HEARTBEAT to { _, _ -> toNode(HeartbeatResponse(currentTimestamp())) },
        OcppActions.STATUS_NOTIFICATION to ::handleStatusNotification,
        OcppActions.METER_VALUES to ::handleMeterValues,
        OcppActions.AUTHORIZE to { _, payload -> handleAuthorize(payload) },
        OcppActions.START_TRANSACTION to { _, payload -> handleStartTransaction(payload) },
        OcppActions.STOP_TRANSACTION to { _, payload -> handleStopTransaction(payload) },
    )

    fun route(chargePointId: ChargerId, call: OcppFrame.Call): OcppFrame {
        val handler = handlersByAction[call.action] ?: return notSupported(call)
        return try {
            OcppFrame.CallResult(call.uniqueId, handler(chargePointId, call.payload))
        } catch (violation: OcppProtocolViolationException) {
            callErrorFrame(call.uniqueId, violation.errorCode, violation.message.orEmpty())
        } catch (malformed: JacksonException) {
            formatViolation(call, malformed)
        } catch (malformed: IllegalArgumentException) {
            formatViolation(call, malformed)
        } catch (unexpected: RuntimeException) {
            logger.error("Fehler bei Verarbeitung von {} von {}", call.action, chargePointId, unexpected)
            callErrorFrame(call.uniqueId, OcppErrorCode.INTERNAL_ERROR, "interner Fehler")
        }
    }

    private fun handleBootNotification(chargePointId: ChargerId, payload: JsonNode): JsonNode {
        val request = decode<BootNotificationRequest>(payload)
        logger.info("BootNotification von {}: {} {}", chargePointId, request.chargePointVendor, request.chargePointModel)
        return toNode(
            BootNotificationResponse(
                status = OCPP_STATUS_ACCEPTED,
                currentTime = currentTimestamp(),
                interval = settings.ocpp.heartbeatInterval.seconds.toInt(),
            ),
        )
    }

    private fun handleStatusNotification(chargePointId: ChargerId, payload: JsonNode): JsonNode {
        val request = decode<StatusNotificationRequest>(payload)
        val status = statusOf(request)
        if (isVehicleConnector(request.connectorId)) {
            tracker.onStatusChanged(chargePointId, status)
        } else {
            logger.debug("StatusNotification {} von Connector {} ignoriert", status, request.connectorId)
        }
        return emptyPayload()
    }

    private fun statusOf(request: StatusNotificationRequest): ChargerStatus =
        ChargerStatusMapping.fromOcpp(request.status)
            ?: throw OcppProtocolViolationException(
                OcppErrorCode.PROPERTY_CONSTRAINT_VIOLATION,
                "unbekannter Status ${request.status}",
            )

    /**
     * Connector 0 meldet den Zustand des Ladepunkts. Der Fahrzeugstatus kommt von Connector 1
     * und darf davon nicht überschrieben werden.
     */
    private fun isVehicleConnector(connectorId: Int): Boolean = connectorId != CONNECTOR_ID_CHARGE_POINT

    private fun transactionIdSeed(): Int = (clock.instant().epochSecond % MAX_TRANSACTION_ID).toInt()

    private fun handleMeterValues(chargePointId: ChargerId, payload: JsonNode): JsonNode {
        val request = decode<MeterValuesRequest>(payload)
        tracker.onMeterValues(chargePointId, request.meterValue)
        return emptyPayload()
    }

    private fun handleAuthorize(payload: JsonNode): JsonNode {
        decode<AuthorizeRequest>(payload)
        return toNode(AuthorizeResponse(acceptedIdTagInfo()))
    }

    private fun handleStartTransaction(payload: JsonNode): JsonNode {
        decode<StartTransactionRequest>(payload)
        return toNode(
            StartTransactionResponse(
                transactionId = nextTransactionId.getAndIncrement(),
                idTagInfo = acceptedIdTagInfo(),
            ),
        )
    }

    private fun handleStopTransaction(payload: JsonNode): JsonNode {
        decode<StopTransactionRequest>(payload)
        return toNode(StopTransactionResponse(acceptedIdTagInfo()))
    }

    private fun acceptedIdTagInfo(): IdTagInfo = IdTagInfo(status = OCPP_STATUS_ACCEPTED)

    private fun currentTimestamp(): String = formatOcppTimestamp(clock.instant())

    private fun notSupported(call: OcppFrame.Call): OcppFrame =
        callErrorFrame(call.uniqueId, OcppErrorCode.NOT_SUPPORTED, "Action ${call.action} wird nicht unterstützt")

    private fun formatViolation(call: OcppFrame.Call, cause: RuntimeException): OcppFrame {
        logger.warn("Payload von {} passt nicht zum Schema: {}", call.action, cause.message)
        return callErrorFrame(call.uniqueId, OcppErrorCode.FORMAT_VIOLATION, "Payload passt nicht zum Schema")
    }

    private inline fun <reified T : Any> decode(payload: JsonNode): T =
        OcppJson.mapper.convertValue(payload, T::class.java)

    private fun toNode(response: Any): JsonNode = OcppJson.mapper.valueToTree(response)

    private fun emptyPayload(): JsonNode = OcppJson.mapper.createObjectNode()
}
