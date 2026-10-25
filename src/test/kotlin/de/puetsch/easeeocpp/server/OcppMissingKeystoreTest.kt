package de.puetsch.easeeocpp.server

import io.qameta.allure.Epic
import io.qameta.allure.Feature
import io.qameta.allure.Story
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Path

private const val MISSING_KEYSTORE_FILE_NAME = "fehlt.p12"

@Epic("OCPP")
@Feature("TLS")
class OcppMissingKeystoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    @Story("Fehlender Keystore bricht den Start mit klarer Meldung ab")
    fun contextStartFailsWithReadableMessageWhenKeystoreIsMissing() {
        val missingKeystore = temporaryDirectory.resolve(MISSING_KEYSTORE_FILE_NAME)

        val failure = assertThrows<Exception> { startContext(missingKeystore) }

        val messages = generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue(
            messages.any { it.contains("Keystore fehlt") && it.contains(MISSING_KEYSTORE_FILE_NAME) },
            "Erwartet Hinweis auf fehlenden Keystore, war: $messages",
        )
    }

    private fun startContext(keystorePath: Path) {
        SpringApplicationBuilder(OcppServerTestContext::class.java)
            .web(WebApplicationType.SERVLET)
            .properties(
                "server.port=0",
                "easee.ocpp.port=${freeTcpPort()}",
                "easee.ocpp.ssl.enabled=true",
                "easee.ocpp.ssl.keystore-path=$keystorePath",
                "easee.ocpp.ssl.keystore-password=unbenutzt",
            )
            .run()
            .close()
    }
}
