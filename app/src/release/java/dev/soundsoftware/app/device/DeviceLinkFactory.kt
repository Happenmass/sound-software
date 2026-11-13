package dev.soundsoftware.app.device

import android.content.Context

object DeviceLinkFactory {
    fun create(context: Context): DeviceLink = BleDeviceClient(context)
}
