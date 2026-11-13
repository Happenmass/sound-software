package dev.soundsoftware.core.device

/** Wire details from protocol §6.4–6.5. Credentials are session-only and never logged. */
object DeviceWifiProtocol {
    class Endpoint(val host: String, val port: Int)

    fun credentials(ssid: String, password: String): ByteArray {
        val name = ssid.toByteArray(Charsets.UTF_8)
        val secret = password.toByteArray(Charsets.UTF_8)
        require(name.size in 1..32 && secret.size in 8..63)
        return try { byteArrayOf(name.size.toByte()) + name + byteArrayOf(secret.size.toByte()) + secret }
        finally { secret.fill(0) }
    }

    fun endpoint(frame: DeviceFrame): Endpoint {
        require(frame.type == 0x1a && frame.command == 5 && frame.successful) { "设备未开启 Wi-Fi 快传，请检查电量后重试" }
        require(frame.payload.size >= 6) { "未知 Wi-Fi 地址响应布局" }
        val octets = (3 downTo 0).map { frame.payload[it].toInt() and 255 }
        require(octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)) { "设备未返回本地热点地址" }
        val port = (frame.payload[4].toInt() and 255) or ((frame.payload[5].toInt() and 255) shl 8)
        require(port in 1..65535) { "设备返回了无效端口" }
        return Endpoint(octets.joinToString("."), port)
    }

    /** Wi-Fi uses the SDK's fixed 18-byte request, literal 1b, as lowercase hex text. */
    fun fileRequest(fileId: Long): String {
        val bytes = D3200Protocol.command(0x1a, 7, D3200Protocol.fileRequest(fileId)).copyOf(18)
        bytes[7] = 18; bytes[8] = 0; bytes[17] = 0x1b
        return hex(bytes)
    }

    fun command(type: Int, command: Int, payload: ByteArray = byteArrayOf()) = hex(D3200Protocol.command(type, command, payload))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
