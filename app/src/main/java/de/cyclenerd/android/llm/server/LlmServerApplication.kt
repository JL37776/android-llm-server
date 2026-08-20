package de.cyclenerd.android.llm.server

import android.app.Application
import android.os.Build
import android.os.Process
import android.os.StrictMode
import androidx.tracing.trace
import de.cyclenerd.android.llm.server.perf.PerformanceManager
import de.cyclenerd.android.llm.server.utils.Logger

/**
 * Custom [Application] for the Local LLM Server.
 *
 * Responsibilities:
 *  - Lift the process priority right out of the gate.
 *  - Re-apply [PerformanceManager] knobs in case the App Startup provider
 *    was disabled (some Android skins strip it on aggressive battery
 *    profiles).
 *  - Disable [StrictMode] in release — it adds branch overhead to common
 *    paths even when no violation occurs.
 *  - Provide a single hot-path-friendly process identity for logging.
 *
 * We intentionally do NOT register any heavy listeners here — early
 * `Application.onCreate` time is the single most precious budget the app
 * has. Anything not strictly needed is delegated to lazy initialisers or
 * App Startup.
 */
class LlmServerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        trace("LlmServerApplication#onCreate") {
            // Lift the application's main thread priority once more for
            // OEMs that reset it after Application#attachBaseContext().
            try {
                Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {
                // best effort
            }

            // Defensive re-apply — PerformanceInitializer should already
            // have run, but if a vendor disabled the InitializationProvider
            // we still want the perf knobs flipped.
            PerformanceManager.applyProcessWide(this)

            // In release builds we disable StrictMode entirely. StrictMode
            // is a development aid that intercepts disk/network I/O on the
            // main thread; the interception itself adds overhead even when
            // no violation fires.
            if (!BuildConfig.PERF_LOGGING) {
                disableStrictMode()
            }

            Logger.i(
                TAG,
                "Application boot complete (sdk=${Build.VERSION.SDK_INT}, " +
                    "model=${Build.MODEL}, soc=${socName()})",
            )
        }
    }

    private fun disableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
    }

    // SOC_MANUFACTURER / SOC_MODEL added in API 31, safe for minSdk 35.
    private fun socName(): String = "${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}"

    private companion object {
        private const val TAG = "LlmServerApplication"
    }
}
