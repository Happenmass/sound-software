package dev.soundsoftware.app.device

import dev.soundsoftware.app.R
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import dev.soundsoftware.core.device.DeviceWifiProtocol
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient

/** App-scoped local network request. Never binds the process or alters saved Wi-Fi networks. */
class RecorderWifiNetwork(private val context: Context) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val available = CompletableDeferred<Network>()
    @Volatile private var lost = false
    @Volatile var onLost: (() -> Unit)? = null
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { available.complete(network) }
        override fun onUnavailable() { available.completeExceptionally(IllegalStateException(context.getString(R.string.hotspot_join_failed))) }
        override fun onLost(network: Network) { lost = true; onLost?.invoke() }
    }

    fun checkPermissionAndWifi() {
        if (Build.VERSION.SDK_INT < 29) error(context.getString(R.string.wifi_os_required))
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) { context.getString(R.string.wifi_permissions_missing) }
        check(context.getSystemService(WifiManager::class.java).isWifiEnabled) { context.getString(R.string.turn_on_wifi) }
    }

    @SuppressLint("MissingPermission")
    suspend fun join(ssid: String, password: String): Network {
        if (Build.VERSION.SDK_INT < 29) error(context.getString(R.string.wifi_os_required))
        checkPermissionAndWifi()
        val specifier = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
        try {
            connectivity.requestNetwork(request, callback)
            registered = true
            return withTimeout(60_000) { available.await() }.also { check(!lost) { context.getString(R.string.hotspot_disconnected) } }
        } catch (e: Exception) { close(); throw e }
    }

    fun checkAvailable() { check(!lost) { context.getString(R.string.wifi_disconnected_retry) } }

    fun explainFailure(error: Exception): Exception {
        val denied = generateSequence<Throwable>(error) { it.cause }.take(5).any {
            (it is android.system.ErrnoException && it.errno in listOf(1, 13)) ||
                it.message.orEmpty().let { message -> message.contains("EPERM") || message.contains("EACCES") || message.contains("errno=1") }
        }
        val vpn = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return if (denied && vpn) IllegalStateException(context.getString(R.string.vpn_blocks_hotspot), error) else error
    }

    fun client(network: Network, endpoint: DeviceWifiProtocol.Endpoint): OkHttpClient {
        // D3200 uses a local self-signed certificate. This dedicated client is restricted to
        // the exact BLE-advertised private IP, no proxies/redirects or cloud requests.
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { error("Client certificates are not supported") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { require(chain.isNotEmpty()) }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return OkHttpClient.Builder().socketFactory(network.socketFactory).proxy(Proxy.NO_PROXY)
            .sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { hostname, _ -> hostname == endpoint.host }
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    }

    suspend fun keepAlive(network: Network, host: String, onPing: () -> Unit = {}) = withContext(Dispatchers.IO) {
        DatagramSocket().use { socket ->
            network.bindSocket(socket)
            val bytes = "soundcore-keep-alive-unicast".toByteArray(Charsets.US_ASCII)
            val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), 32003)
            while (isActive) { socket.send(packet); onPing(); delay(2000) }
        }
    }

    override fun close() {
        onLost = null
        if (registered) { registered = false; runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }
}
