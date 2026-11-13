package dev.soundsoftware.app.inference

import android.os.Build
import android.system.Os
import android.system.OsConstants

data class QnnDevice(val soc:String,val arm64:Boolean,val emulator:Boolean,val pageSize:Long) {
    val supportsSm8850Htp: Boolean
        get() = arm64 && !emulator && soc.startsWith("SM8850", ignoreCase = true) && pageSize == 4096L

    companion object {
        fun current() = QnnDevice(
            soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "",
            arm64 = Build.SUPPORTED_ABIS.contains("arm64-v8a"),
            emulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.FINGERPRINT.startsWith("generic"),
            pageSize = Os.sysconf(OsConstants._SC_PAGESIZE),
        )
    }
}
