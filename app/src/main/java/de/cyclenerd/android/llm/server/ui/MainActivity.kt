package de.cyclenerd.android.llm.server.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tracing.Trace
import de.cyclenerd.android.llm.server.data.AppPreferences
import de.cyclenerd.android.llm.server.perf.PerformanceManager
import de.cyclenerd.android.llm.server.service.LlmServerService
import de.cyclenerd.android.llm.server.ui.theme.LocalLLMServerTheme
import de.cyclenerd.android.llm.server.utils.Logger
import kotlinx.coroutines.launch

/**
 * Main Activity — entry point for the Local LLM Server UI.
 *
 * MAXIMUM PERFORMANCE MODE:
 * Process-wide CPU/GPU optimisations are applied via [PerformanceManager]
 * **before** this activity is created (see
 * [de.cyclenerd.android.llm.server.perf.PerformanceInitializer]).
 *
 * This activity is responsible for the **window-scoped** tweaks that can
 * only happen with an Activity context:
 *  - Sustained Performance Mode on the [android.view.Window].
 *  - Maximum display refresh rate.
 *  - Screen-on flag (game mode).
 *  - High-Performance WiFi lock so the radio doesn't enter PSP mode.
 *  - Battery optimisation exemption prompt (one-time user dialog).
 *
 * Lifecycle policy:
 *  - We DO NOT release any of these locks in [onPause] / [onStop]. Behaving
 *    like a game means burning battery while open — the user explicitly
 *    opted in by launching this app.
 */
class MainActivity : ComponentActivity() {
    private var hiPerfWifiLock: WifiManager.WifiLock? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Logger.i(TAG, "✓ Notification permission granted")
            } else {
                Logger.w(TAG, "⚠ Notification permission denied - notifications will not be shown")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Trace.beginSection("MainActivity#onCreate")
        try {
            enableEdgeToEdge()

            Logger.i(TAG, "MainActivity created — applying window-scope performance tweaks")

            // 1. Process-wide knobs (idempotent — usually already applied
            // by PerformanceInitializer, but defensive).
            PerformanceManager.applyProcessWide(applicationContext)

            // 2. Window-scope knobs: sustained perf, KEEP_SCREEN_ON, max
            // refresh rate.
            PerformanceManager.applyWindow(window)

            // 3. WiFi high-performance lock — prevents the radio from
            // dropping into power-save mode while we're serving requests.
            acquireHighPerfWifiLock()

            // 4. Boost the UI thread one more time (some OEMs reset it).
            try {
                Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {
                // best effort
            }

            // 5. Request notification permission (Android 13+)
            requestNotificationPermission()

            // 6. Ask the user to disable battery optimisations the first
            // time the app is launched. Required for true 24/7 inference.
            requestBatteryOptimizationExemption()

            setContent {
                LocalLLMServerTheme {
                    AppNavigation()
                }
            }

            Logger.i(TAG, "════════════════════════════════════════════════")
            Logger.i(TAG, "  MAXIMUM PERFORMANCE MODE ACTIVE")
            Logger.i(TAG, "  - CPU: pinned to big cores, URGENT_AUDIO prio")
            Logger.i(TAG, "  - GPU: full power (KEEP_SCREEN_ON + sustained)")
            Logger.i(TAG, "  - Display: max refresh rate")
            Logger.i(TAG, "  - WiFi: high-perf lock acquired")
            Logger.i(TAG, "════════════════════════════════════════════════")

            // 7. Handle auto-start if enabled
            handleAutoStart()
        } finally {
            Trace.endSection()
        }
    }

    private fun handleAutoStart() {
        lifecycleScope.launch {
            try {
                val preferences = AppPreferences(applicationContext)
                val autoStartEnabled = preferences.getAutoStartServer()

                if (autoStartEnabled) {
                    Logger.i(TAG, "Auto-start enabled, starting server...")
                    LlmServerService.start(applicationContext)
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Error checking auto-start preference", e)
            }
        }
    }

    private fun acquireHighPerfWifiLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

            // WIFI_MODE_FULL_HIGH_PERF is deprecated since API 29 (the
            // framework manages WiFi power itself now), but it is still the
            // only documented hint to keep the radio in High-Performance
            // Mode. We accept the deprecation — that is exactly what we want.
            @Suppress("DEPRECATION")
            val lock =
                wifi?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "LlmServer::HiPerfWifiLock",
                )
            lock?.setReferenceCounted(false)
            lock?.acquire()
            hiPerfWifiLock = lock
            if (lock != null) Logger.i(TAG, "✓ Hi-perf WiFi lock acquired")
        } catch (t: Throwable) {
            Logger.w(TAG, "Could not acquire WiFi lock: ${t.message}")
        }
    }

    private fun releaseHighPerfWifiLock() {
        try {
            hiPerfWifiLock?.takeIf { it.isHeld }?.release()
            hiPerfWifiLock = null
        } catch (_: Throwable) {
            // best effort
        }
    }

    /**
     * Requests notification permission on Android 13+ (API 33+).
     *
     * Android 13 introduced runtime permission for notifications.
     * Without this permission, foreground service notifications
     * won't be shown, which can cause the service to be killed.
     *
     * On Android 12 and below, notifications are allowed by default.
     *
     * This is called once on app launch. If the user denies permission,
     * they can grant it later in Settings → Apps → Notifications.
     */
    private fun requestNotificationPermission() {
        // minSdk = 35, so we're always on Android 13+ where POST_NOTIFICATIONS exists
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED -> {
                Logger.i(TAG, "✓ Notification permission already granted")
            }

            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                // User previously denied permission. Show rationale and request again.
                Logger.i(TAG, "Requesting notification permission (user previously denied)")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            else -> {
                // First time requesting permission
                Logger.i(TAG, "Requesting notification permission (first time)")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * One-time prompt asking the user to disable battery optimisations.
     * Without this, Android Doze can throttle inference even with our
     * wake locks held.
     *
     * The Play Store discourages [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
     * because it is rarely justified for consumer apps. This app is a
     * long-running on-device LLM HTTP server: WorkManager / JobScheduler
     * cannot model "must run for hours streaming tokens", so battery
     * optimisations have to be lifted by the user. Suppressing the
     * `BatteryLife` lint warning since the use-case is intentional and
     * documented.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemption() {
        // ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS requires API 23, safe for minSdk 35.
        try {
            val intent =
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
            startActivity(intent)
            Logger.i(TAG, "⚠ Battery optimization exemption requested — USER MUST APPROVE")
        } catch (e: Exception) {
            Logger.w(TAG, "Could not request battery exemption: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseHighPerfWifiLock()
        Logger.i(TAG, "MainActivity destroyed")
    }

    private companion object {
        private const val TAG = "MainActivity"
    }
}

/**
 * Navigation host for the app.
 *
 *  - "dashboard" — main screen with server controls
 *  - "models" — model management screen
 *  - "settings" — startup settings screen (auto-start)
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "dashboard",
    ) {
        composable("dashboard") {
            DashboardScreen(
                onNavigateToModels = {
                    Logger.i("Navigation", "Navigating to model management")
                    navController.navigate("models")
                },
                onNavigateToSettings = {
                    Logger.i("Navigation", "Navigating to settings")
                    navController.navigate("settings")
                },
            )
        }
        composable("models") {
            ModelManagementScreen(
                onNavigateBack = {
                    Logger.i("Navigation", "Navigating back to dashboard")
                    navController.popBackStack()
                },
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    Logger.i("Navigation", "Navigating back to dashboard")
                    navController.popBackStack()
                },
            )
        }
    }
}
