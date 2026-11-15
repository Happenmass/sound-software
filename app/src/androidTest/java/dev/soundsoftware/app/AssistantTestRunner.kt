package dev.soundsoftware.app

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import java.util.UUID
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/** Assistant tests use a temporary recording library, with independent settings for the backend handoff. */
class AssistantTestRunner : AndroidJUnitRunner() {
    private var useFixtureLibrary = false
    private var isolateSettings = false
    private var fixtureCertificate: String? = null

    override fun onCreate(arguments: Bundle) {
        isolateSettings = arguments.getString("agentPhone") == "true"
        useFixtureLibrary = arguments.getString("realPi") == "true" || isolateSettings
        fixtureCertificate = arguments.getString("liveTlsCert").takeIf { useFixtureLibrary }
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        if (useFixtureLibrary && app is SoundApplication) {
            app.instrumentationIsolateSettings = isolateSettings
            app.instrumentationAssistantClient = fixtureCertificate?.let(::gatewayClient)
            app.instrumentationDataDir = File(app.cacheDir, "pi-fixture-${UUID.randomUUID()}")
        }
        super.callApplicationOnCreate(app)
    }

    private fun gatewayClient(base64: String): OkHttpClient {
        val der = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("live-gateway", certificate)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val manager = trust.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, manager).build()
    }

}
