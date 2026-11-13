package dev.soundsoftware.app

import android.app.Application
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import java.util.UUID

/** The real-provider probe uses the installed model settings with an empty, temporary recording library. */
class AssistantTestRunner : AndroidJUnitRunner() {
    private var useFixtureLibrary = false

    override fun onCreate(arguments: Bundle) {
        useFixtureLibrary = arguments.getString("realPi") == "true"
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        if (useFixtureLibrary && app is SoundApplication) {
            app.instrumentationDataDir = File(app.cacheDir, "pi-fixture-${UUID.randomUUID()}")
        }
        super.callApplicationOnCreate(app)
    }
}
