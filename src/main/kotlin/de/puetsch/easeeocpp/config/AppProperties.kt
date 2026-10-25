package de.puetsch.easeeocpp.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "easee")
data class AppProperties(
    val ha: HaProperties = HaProperties(),
    val control: ControlProperties = ControlProperties(),
    val ocpp: OcppProperties = OcppProperties(),
    val cloud: EaseeCloudProperties = EaseeCloudProperties(),
    val chargers: List<ChargerProperties> = listOf(
        ChargerProperties(id = "EHDFQLBX", name = "Mülltonnen"),
        ChargerProperties(id = "EH2NSSVK", name = "Eingangsseite"),
    ),
)

data class ChargerProperties(val id: String, val name: String)

data class HaProperties(
    val url: String = "ws://homeassistant.fritz.box:8123/api/websocket",
    val token: String = "",
    val heartbeatInterval: Duration = Duration.ofSeconds(30),
    val heartbeatEntity: String = "input_datetime.easee_service_heartbeat",
    val reconnectBackoffMin: Duration = Duration.ofSeconds(1),
    val reconnectBackoffMax: Duration = Duration.ofSeconds(60),
    val entities: HaEntities = HaEntities(),
)

data class HaEntities(
    val gridPower: String = "sensor.ehz_zaehler_sml_power_curr",
    val batteryPower: String = "sensor.venus_system_vebus_power",
    val pvPower: String = "sensor.solaredge_modbus_ac_power",
    val minimumEvCurrent: String = "input_number.minimum_ev_current",
    val pvSurplusCharge: String = "input_boolean.pv_surplus_charge",
    val targetPhases: String = "input_select.target_ev_phases",
    val targetCurrent: String = "input_number.target_ev_current",
    val securityBuffer: String = "input_number.ev_surplus_charge_security_buffer",
    val preferCarOverBattery: String = "input_boolean.car_preference_over_pv_battery",
    val priorityCharger: String = "input_select.ev_priority_charger",
)

data class ControlProperties(
    val interval: Duration = Duration.ofSeconds(5),
    val settleDelay: Duration = Duration.ofSeconds(15),
    val gridVoltage: Int = 240,
    val minHousePower: Int = 100,
    val minChargeCurrent: Int = 6,
    val maxChargeCurrent: Int = 32,
    val priorityCapOnePhase: Int = 32,
    val priorityCapThreePhases: Int = 16,
    val circuitMaxCurrentPerPhase: Int = 32,
    val actuationEnabled: Boolean = false,
    val profileRefreshInterval: Duration = Duration.ofSeconds(60),
    val ineligibleAfter: Duration = Duration.ofSeconds(120),
    val pauseDelay: Duration = Duration.ofSeconds(60),
    val allowRebalanceForMinCurrent: Boolean = false,
    val priorityIdleAfter: Duration = Duration.ofMinutes(2),
    val idlePowerThreshold: Double = 100.0,
    val historySize: Int = 50,
    val historyFile: String = "data/control-history.jsonl",
)

data class OcppProperties(
    val port: Int = 8443,
    val path: String = "/ocpp",
    val basicAuthPassword: String = "",
    val allowAnonymous: Boolean = false,
    val meterValueSampleInterval: Duration = Duration.ofSeconds(30),
    val heartbeatInterval: Duration = Duration.ofSeconds(300),
    val profileDuration: Duration = Duration.ofSeconds(300),
    val freeChargingIdTag: String = "FREECHARGING",
    val useNumberPhases: Boolean = false,
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val meterValueMaxAge: Duration = Duration.ofSeconds(90),
    val ssl: OcppSslProperties = OcppSslProperties(),
)

data class OcppSslProperties(
    val enabled: Boolean = true,
    val keystorePath: String = "secrets/keystore.p12",
    val keystorePassword: String = "",
)

data class EaseeCloudProperties(
    val baseUrl: String = "https://api.easee.com/api",
    val username: String = "",
    val password: String = "",
)
