package de.puetsch.easeeocpp.testsupport

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

private const val KEY_ALGORITHM = "EC"
private const val KEY_CURVE_SIZE = 256
private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
private const val CERTIFICATE_VALIDITY_DAYS = 2L
private const val SERVER_ALIAS = "ocpp-test-server"
private const val KEYSTORE_TYPE = "PKCS12"
private const val TLS_PROTOCOL = "TLS"
private const val SERVER_HOST_NAME = "localhost"
private const val SERVER_IP_ADDRESS = "127.0.0.1"
private const val SERIAL_BITS = 64
private const val CA_NAME = "CN=Easee OCPP Test-CA"
private const val SERVER_NAME = "CN=localhost"
private const val CLOCK_SKEW_MINUTES = 5L

private const val TEST_KEYSTORE_PASSWORD = "test-keystore-passwort"

/**
 * Erzeugt zur Laufzeit eine Test-CA und ein Serverzertifikat (SAN localhost/127.0.0.1) als PKCS12-Keystore.
 * Clients bekommen einen SSLContext, der nur dieser CA vertraut.
 */
object TestCertificates {
    val keystorePassword: String = TEST_KEYSTORE_PASSWORD

    private val keystoreFile: Path by lazy { writeServerKeystore() }

    fun keystorePath(): String = keystoreFile.toString()

    fun trustingSslContext(): SSLContext {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
        trustStore.load(null, null)
        trustStore.setCertificateEntry("ca", caCertificate)
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(trustStore)
        return SSLContext.getInstance(TLS_PROTOCOL).apply {
            init(null, trustManagerFactory.trustManagers, null)
        }
    }

    private val caKeyPair: KeyPair by lazy { generateKeyPair() }

    private val caCertificate: X509Certificate by lazy { createCaCertificate() }

    private val serverKeyPair: KeyPair by lazy { generateKeyPair() }

    private val serverCertificate: X509Certificate by lazy { createServerCertificate() }

    private fun writeServerKeystore(): Path {
        val keystore = KeyStore.getInstance(KEYSTORE_TYPE)
        keystore.load(null, null)
        keystore.setKeyEntry(
            SERVER_ALIAS,
            serverKeyPair.private,
            keystorePassword.toCharArray(),
            arrayOf(serverCertificate, caCertificate),
        )
        val file = Files.createTempFile("ocpp-test-keystore-", ".p12")
        file.toFile().deleteOnExit()
        Files.newOutputStream(file).use { output -> keystore.store(output, keystorePassword.toCharArray()) }
        return file
    }

    private fun createCaCertificate(): X509Certificate {
        val validFrom = validFrom()
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            X500Name(CA_NAME),
            randomSerial(),
            validFrom,
            validUntil(validFrom),
            X500Name(CA_NAME),
            caKeyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        return sign(builder, caKeyPair)
    }

    private fun createServerCertificate(): X509Certificate {
        val validFrom = validFrom()
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            X500Name(CA_NAME),
            randomSerial(),
            validFrom,
            validUntil(validFrom),
            X500Name(SERVER_NAME),
            serverKeyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
        )
        builder.addExtension(Extension.subjectAlternativeName, false, subjectAlternativeNames())
        return sign(builder, caKeyPair)
    }

    private fun subjectAlternativeNames(): GeneralNames = GeneralNames(
        arrayOf(
            GeneralName(GeneralName.dNSName, SERVER_HOST_NAME),
            GeneralName(GeneralName.iPAddress, SERVER_IP_ADDRESS),
        ),
    )

    private fun sign(builder: X509v3CertificateBuilder, signingKeyPair: KeyPair): X509Certificate {
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(signingKeyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALGORITHM).apply { initialize(KEY_CURVE_SIZE) }.generateKeyPair()

    private fun randomSerial(): BigInteger = BigInteger(SERIAL_BITS, SecureRandom())

    private fun validFrom(): Date = Date.from(Instant.now().minus(Duration.ofMinutes(CLOCK_SKEW_MINUTES)))

    private fun validUntil(validFrom: Date): Date =
        Date.from(validFrom.toInstant().plus(Duration.ofDays(CERTIFICATE_VALIDITY_DAYS)))

}
