package dev.soundsoftware.app.agent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal object PiNode {
    val started = AtomicBoolean()
    external fun run(script: String, socket: String): Int
}

class PiNodeService : Service() {
    override fun onBind(intent: Intent): IBinder {
        if (PiNode.started.compareAndSet(false, true)) {
            Thread({
                try {
                    val script = File(cacheDir, "pi-host.cjs")
                    assets.open(script.name).use { input -> script.outputStream().use(input::copyTo) }
                    val socket = File(cacheDir, PI_SOCKET)
                    socket.delete()
                    System.loadLibrary("sound_pi_jni")
                    val result = PiNode.run(script.path, socket.path)
                    Log.e("PiNodeService", "Node exited with status $result")
                } catch (error: Exception) {
                    Log.e("PiNodeService", "Could not start the bundled Pi runtime", error)
                } catch (error: LinkageError) {
                    Log.e("PiNodeService", "Could not load the bundled Node library", error)
                } finally {
                    // node::Start is process-wide. A new binding can start a fresh worker.
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }, "Pi Node runtime").start()
        }
        return Binder()
    }
}

internal const val PI_SOCKET = "pi.sock"
