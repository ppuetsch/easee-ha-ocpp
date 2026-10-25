package de.puetsch.easeeocpp.server

import de.puetsch.easeeocpp.config.AppProperties
import org.apache.coyote.http11.Http11NioProtocol
import org.apache.tomcat.util.net.SSLHostConfig
import org.apache.tomcat.util.net.SSLHostConfigCertificate
import org.apache.tomcat.util.net.SSLHostConfigCertificate.Type
import org.apache.catalina.connector.Connector
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

private const val TLS_PROTOCOLS = "TLSv1.2,TLSv1.3"
private const val KEYSTORE_TYPE = "PKCS12"
private const val SSL_ENABLED_PROPERTY = "SSLEnabled"
private const val HTTPS_SCHEME = "https"

/**
 * Baut den zusätzlichen Connector für den OCPP-Port. Mit TLS aus dem PKCS12-Keystore, sonst Plain-HTTP
 * (nur für Tests und Entwicklung).
 */
class OcppConnectorFactory(private val settings: AppProperties) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun createConnector(): Connector =
        if (settings.ocpp.ssl.enabled) createTlsConnector() else createPlainConnector()

    private fun createTlsConnector(): Connector {
        val keystorePath = requireExistingKeystore(settings.ocpp.ssl.keystorePath)
        val connector = newConnector()
        connector.scheme = HTTPS_SCHEME
        connector.secure = true
        connector.setProperty(SSL_ENABLED_PROPERTY, "true")
        connector.addSslHostConfig(sslHostConfig(keystorePath))
        return connector
    }

    private fun createPlainConnector(): Connector {
        logger.warn(
            "OCPP-Port {} läuft ohne TLS (easee.ocpp.ssl.enabled=false). Nur für Tests/Entwicklung verwenden.",
            settings.ocpp.port,
        )
        return newConnector()
    }

    private fun newConnector(): Connector {
        val connector = Connector(Http11NioProtocol::class.java.name)
        connector.port = settings.ocpp.port
        return connector
    }

    private fun sslHostConfig(keystorePath: Path): SSLHostConfig {
        val sslHostConfig = SSLHostConfig()
        sslHostConfig.setProtocols(TLS_PROTOCOLS)
        val certificate = SSLHostConfigCertificate(sslHostConfig, Type.UNDEFINED)
        certificate.certificateKeystoreFile = keystorePath.toAbsolutePath().toString()
        certificate.certificateKeystoreType = KEYSTORE_TYPE
        certificate.certificateKeystorePassword = settings.ocpp.ssl.keystorePassword
        sslHostConfig.addCertificate(certificate)
        return sslHostConfig
    }

    private fun requireExistingKeystore(configuredPath: String): Path {
        val keystorePath = Path.of(configuredPath)
        check(Files.isRegularFile(keystorePath)) {
            "easee.ocpp.ssl.enabled=true, aber der Keystore fehlt: '$configuredPath' " +
                "(erwartet PKCS12, Pfad über easee.ocpp.ssl.keystore-path setzen)."
        }
        return keystorePath
    }
}
