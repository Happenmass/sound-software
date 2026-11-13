package dev.soundsoftware.app.device

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Only the opt-in hardware probe uses this foreground surface; no production BLE owner. */
class WifiProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "正在验证录音豆 Wi-Fi 传输，请保持设备靠近手机。"
            textSize = 20f
            setPadding(48, 120, 48, 48)
        })
    }
}
