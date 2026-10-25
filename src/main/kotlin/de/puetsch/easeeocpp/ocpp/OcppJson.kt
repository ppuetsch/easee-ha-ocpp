package de.puetsch.easeeocpp.ocpp

import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Instant
import java.time.temporal.ChronoUnit

object OcppJson {
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()
}

/** OCPP verlangt UTC mit `Z`; Sekundenbruchteile werden weggelassen, damit das Format deterministisch bleibt. */
fun formatOcppTimestamp(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()
