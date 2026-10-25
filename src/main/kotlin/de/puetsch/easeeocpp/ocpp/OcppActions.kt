package de.puetsch.easeeocpp.ocpp

const val OCPP_SUBPROTOCOL = "ocpp1.6"

object OcppActions {
    const val BOOT_NOTIFICATION = "BootNotification"
    const val HEARTBEAT = "Heartbeat"
    const val STATUS_NOTIFICATION = "StatusNotification"
    const val METER_VALUES = "MeterValues"
    const val AUTHORIZE = "Authorize"
    const val START_TRANSACTION = "StartTransaction"
    const val STOP_TRANSACTION = "StopTransaction"
    const val SET_CHARGING_PROFILE = "SetChargingProfile"
    const val CHANGE_CONFIGURATION = "ChangeConfiguration"
    const val GET_CONFIGURATION = "GetConfiguration"
    const val TRIGGER_MESSAGE = "TriggerMessage"
}
