package de.puetsch.easeeocpp.ocpp

import de.puetsch.easeeocpp.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * RFID-Freigabe: Nur Tags aus `easee.ocpp.allowed-id-tags` werden akzeptiert. Die Liste ist fail-closed,
 * eine leere Liste lehnt alle Karten ab. Der Vergleich ignoriert Groß-/Kleinschreibung und Leerzeichen am Rand.
 */
@Component
class IdTagWhitelist(settings: AppProperties) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val allowedNormalizedTags: Set<String> = settings.ocpp.allowedIdTags
        .map(::normalizeIdTag)
        .filter { it.isNotEmpty() }
        .toSet()

    fun isAllowed(idTag: String): Boolean = normalizeIdTag(idTag) in allowedNormalizedTags

    fun hasEntries(): Boolean = allowedNormalizedTags.isNotEmpty()

    @EventListener(ApplicationReadyEvent::class)
    fun warnIfEmpty() {
        if (hasEntries()) return
        logger.warn("OCPP: EASEE_OCPP_ALLOWEDIDTAGS ist leer - ALLE RFID-Karten werden abgelehnt.")
    }
}

private fun normalizeIdTag(idTag: String): String = idTag.trim().lowercase()
