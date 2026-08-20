package de.cyclenerd.android.llm.server.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.cyclenerd.android.llm.server.inference.PerformanceMetrics
import de.cyclenerd.android.llm.server.network.NetworkUtils
import de.cyclenerd.android.llm.server.service.LlmServerService
import de.cyclenerd.android.llm.server.service.ServiceState
import de.cyclenerd.android.llm.server.ui.components.LogEntry
import de.cyclenerd.android.llm.server.utils.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Dashboard screen.
 *
 * Responsibilities:
 * - Manages UI state (server status, IPs, metrics, logs)
 * - Coordinates service control (start/stop server)
 * - Exposes data as StateFlows for reactive UI
 * - Handles business logic (no UI code)
 *
 * Why ViewModel?
 * ViewModel survives configuration changes (screen rotation, dark mode toggle).
 * Without ViewModel:
 * - Screen rotation → Activity destroyed → Data lost
 * - User starts server, rotates screen → Server status lost
 * - Metrics disappear on rotation
 *
 * With ViewModel:
 * - Activity destroyed → ViewModel retained
 * - Data persists across rotations
 * - Server keeps running, UI reconnects to same state
 *
 * Why AndroidViewModel?
 * Needs Application context to:
 * - Start/stop foreground service
 * - Access network utilities
 * - Get system services
 *
 * Regular ViewModel doesn't have context. AndroidViewModel provides
 * application context (safe to hold long-term, doesn't leak).
 *
 * StateFlow vs LiveData:
 * - StateFlow: Kotlin Coroutines, works everywhere, type-safe
 * - LiveData: Android-specific, lifecycle-aware, older API
 *
 * We use StateFlow because:
 * - Better with Compose (collectAsState)
 * - Works with coroutines (our app is coroutine-heavy)
 * - More flexible (can transform, combine flows)
 * - Kotlin standard (not Android-only)
 *
 * ViewModelScope:
 * Coroutine scope tied to ViewModel lifecycle. When ViewModel
 * is cleared (Activity fully destroyed), all jobs are cancelled.
 *
 * This prevents:
 * - Memory leaks (coroutines holding ViewModel reference)
 * - Crashes (updating destroyed UI)
 * - Battery drain (background work after user leaves)
 *
 * Separation of Concerns:
 * ViewModel knows nothing about Compose, Views, or UI code.
 * It only:
 * - Exposes data (StateFlows)
 * - Handles events (functions)
 * - Coordinates business logic
 *
 * This makes it:
 * - Testable (no Android dependencies)
 * - Reusable (could use with Views instead of Compose)
 * - Maintainable (UI changes don't affect logic)
 *
 * @param application Application context
 */
class DashboardViewModel(
    application: Application,
) : AndroidViewModel(application) {
    // Server state (Running, Stopped, etc.)
    private val _serverState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serverState: StateFlow<ServiceState> = _serverState.asStateFlow()

    // Local IP addresses where server is accessible
    private val _ipAddresses = MutableStateFlow<List<String>>(emptyList())
    val ipAddresses: StateFlow<List<String>> = _ipAddresses.asStateFlow()

    // Latest performance metrics from inference
    private val _latestMetrics = MutableStateFlow<PerformanceMetrics?>(null)
    val latestMetrics: StateFlow<PerformanceMetrics?> = _latestMetrics.asStateFlow()

    // Request logs (last 50)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Error message for popup dialog (persists even after state changes)
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val stateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                // Type-safe getParcelableExtra requires API 33, safe for minSdk 35.
                val state = intent?.getParcelableExtra(LlmServerService.EXTRA_SERVICE_STATE, ServiceState::class.java)
                state?.let {
                    Logger.i(TAG, "Received service state: $it")
                    _serverState.value = it

                    // Capture error message for popup dialog
                    if (it is ServiceState.Error) {
                        _errorMessage.value = it.message
                    }
                }
            }
        }

    private val metricsReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                // Type-safe getParcelableExtra requires API 33, safe for minSdk 35.
                val metrics = intent?.getParcelableExtra(LlmServerService.EXTRA_METRICS, PerformanceMetrics::class.java)
                metrics?.let {
                    Logger.d(TAG, "Received metrics: ${it.decodeTokensPerSecond} tk/s")
                    _latestMetrics.value = it
                }
            }
        }

    private val logReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                val timestamp = intent?.getLongExtra(LlmServerService.EXTRA_LOG_TIMESTAMP, 0L) ?: return
                val method = intent.getStringExtra(LlmServerService.EXTRA_LOG_METHOD) ?: return
                val path = intent.getStringExtra(LlmServerService.EXTRA_LOG_PATH) ?: return
                val sourceIp = intent.getStringExtra(LlmServerService.EXTRA_LOG_SOURCE_IP) ?: "unknown"
                val statusCode = intent.getIntExtra(LlmServerService.EXTRA_LOG_STATUS, 0)

                // Type-safe getParcelableExtra requires API 33, safe for minSdk 35.
                val metrics = intent.getParcelableExtra(LlmServerService.EXTRA_LOG_METRICS, PerformanceMetrics::class.java)

                val logEntry =
                    LogEntry(
                        timestamp = timestamp,
                        method = method,
                        path = path,
                        sourceIp = sourceIp,
                        statusCode = statusCode,
                        metrics = metrics,
                    )

                addLog(logEntry)
            }
        }

    init {
        // Initialize state
        updateNetworkInfo()

        val context = getApplication<Application>()

        // Subscribe to service state broadcasts
        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            IntentFilter(LlmServerService.ACTION_SERVICE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Subscribe to metrics updates
        ContextCompat.registerReceiver(
            context,
            metricsReceiver,
            IntentFilter(LlmServerService.ACTION_METRICS_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Subscribe to request logs
        ContextCompat.registerReceiver(
            context,
            logReceiver,
            IntentFilter(LlmServerService.ACTION_REQUEST_LOG),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCleared() {
        super.onCleared()
        val context = getApplication<Application>()
        context.unregisterReceiver(stateReceiver)
        context.unregisterReceiver(metricsReceiver)
        context.unregisterReceiver(logReceiver)
    }

    /**
     * Toggles the server (start if stopped, stop if running).
     *
     * This is called when user taps the start/stop button.
     *
     * State transitions:
     * - Stopped → Start service → Starting → Running
     * - Running → Stop service → Stopping → Stopped
     *
     * We launch in viewModelScope so:
     * - Coroutine automatically cancelled if ViewModel cleared
     * - No blocking on main thread
     * - Proper error handling with try/catch
     */
    fun toggleServer() {
        viewModelScope.launch {
            try {
                when (_serverState.value) {
                    is ServiceState.Stopped,
                    is ServiceState.Error,
                    -> {
                        // Start server - state will be updated via broadcast
                        Logger.i(TAG, "Starting server...")
                        LlmServerService.start(getApplication())
                    }

                    is ServiceState.Running -> {
                        // Stop server - state will be updated via broadcast
                        Logger.i(TAG, "Stopping server...")
                        LlmServerService.stop(getApplication())
                    }

                    else -> {
                        // Ignore toggle during transitions
                        Logger.w(TAG, "Server is transitioning, ignoring toggle")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to toggle server", e)
                _serverState.value = ServiceState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Clears all request logs.
     *
     * This is called when user taps "Clear" button in logs section.
     */
    fun clearLogs() {
        _logs.value = emptyList()
        Logger.i(TAG, "Logs cleared")
    }

    /**
     * Clears the error message.
     *
     * Called when user dismisses the error dialog.
     */
    fun clearError() {
        _errorMessage.value = null
        Logger.i(TAG, "Error cleared")
    }

    /**
     * Updates network information (local IPs).
     *
     * Called on initialization and when network changes.
     */
    private fun updateNetworkInfo() {
        viewModelScope.launch {
            try {
                val ips = NetworkUtils.getLocalIpAddresses()
                _ipAddresses.value = ips
                Logger.i(TAG, "Network info updated: $ips")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to get network info", e)
                _ipAddresses.value = emptyList()
            }
        }
    }

    /**
     * Adds a log entry.
     *
     * Keeps only last 50 entries to prevent memory bloat.
     *
     * Why 50?
     * - Enough for debugging (see recent requests)
     * - Not too many (avoid memory issues)
     * - Fast rendering (LazyColumn performs well)
     *
     * This will be called by request logging system in Epic 08.
     */
    fun addLog(entry: LogEntry) {
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, entry) // Add at the top (newest first)

        // Keep only last 50
        if (currentLogs.size > 50) {
            currentLogs.removeAt(currentLogs.size - 1) // Remove oldest (at the end)
        }

        _logs.value = currentLogs
    }

    companion object {
        private const val TAG = "DashboardViewModel"
    }
}
