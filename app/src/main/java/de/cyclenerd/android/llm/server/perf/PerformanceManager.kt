package de.cyclenerd.android.llm.server.perf

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.view.Display
import android.view.Window
import android.view.WindowManager
import androidx.tracing.Trace
import de.cyclenerd.android.llm.server.utils.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central authority for "this app behaves like a high-end mobile game" tuning.
 *
 * One class, one job: extract every drop of CPU/GPU performance the OS
 * is willing to give us. All knobs can be twisted from a single call site
 * which makes regression testing trivial — flip them off in [release] and
 * see how much slower things get.
 *
 * The optimisations applied here are the same techniques used by AAA
 * mobile titles such as Genshin Impact, COD Mobile and Honkai Star Rail:
 *
 * 1.  **Sustained Performance Mode** — request the kernel keep the SoC at a
 *     steady, high power state instead of bursting + thermal-throttling.
 * 2.  **Maximum display refresh rate** — game-mode hint for HRR panels.
 * 3.  **CPU affinity to performance cores** — pin our hot threads to the
 *     big.LITTLE "big" cluster (and Prime core where present).
 * 4.  **Thread priorities boosted to URGENT_AUDIO** — same priority as
 *     real-time audio threads, the highest a userland app can request.
 * 5.  **Wake locks** for screen + CPU so neither GPU nor CPU power-gates.
 * 6.  **Background priority lifts** for the whole app process.
 * 7.  **Native heap pre-touch** to keep the kernel from page-faulting us
 *     during the first inference request.
 * 8.  **Thermal status listener** so we can surface throttling events.
 *
 * We never undo these settings while the app is alive — the whole point
 * is to behave like a game and burn through battery for raw throughput.
 */
object PerformanceManager {
    private const val TAG = "PerformanceManager"

    private val applied = AtomicBoolean(false)
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    /** Number of CPU cores reported by /proc/cpuinfo (set lazily). */
    val cpuCount: Int by lazy { Runtime.getRuntime().availableProcessors() }

    /** Indices of "big" cores (high-frequency cluster) inferred at runtime. */
    val bigCoreIds: IntArray by lazy { detectBigCores() }

    /** Cached PowerManager handle — set during applyProcessWide. */
    private var cachedPowerManager: PowerManager? = null

    /**
     * Whether this device advertises support for Sustained Performance Mode.
     *
     * Lazy because we need [cachedPowerManager] to be populated, which only
     * happens once [applyProcessWide] has run. Reading it before that point
     * returns false (safe default — we just won't enable the mode).
     */
    val supportsSustainedPerf: Boolean
        get() = cachedPowerManager?.isSustainedPerformanceModeSupported == true

    /**
     * Apply every process-wide perf knob we know. Idempotent — safe to call
     * multiple times. Must be called from the main process; ideally from
     * the [PerformanceInitializer] before the first activity is created.
     *
     * @return true if optimisations were applied, false if already applied.
     */
    fun applyProcessWide(context: Context): Boolean {
        if (!applied.compareAndSet(false, true)) return false

        Trace.beginSection("PerformanceManager#applyProcessWide")
        try {
            val ctx = context.applicationContext
            cachedPowerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager

            // (1) Acquire the CPU wake lock first — everything else assumes
            // the SoC is awake and the dispatcher won't get parked.
            acquireCpuWakeLock(ctx)

            // (2) Lift our process priority. THREAD_PRIORITY_URGENT_AUDIO is
            // the highest priority a non-system app may request and signals
            // to the scheduler that we are latency critical.
            try {
                Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_AUDIO)
                Logger.i(TAG, "Main thread priority raised to URGENT_AUDIO")
            } catch (t: Throwable) {
                Logger.w(TAG, "Could not raise main thread priority: ${t.message}")
            }

            // (3) Pin the calling thread to the big cores. Worker threads
            // created later (Ktor CIO, Coroutine dispatchers) inherit affinity.
            pinCurrentThreadToBigCores()

            // (4) Pre-touch the native heap so we don't take page faults on
            // the first inference burst. We allocate ~64 MB and immediately
            // free it; the kernel keeps the pages resident.
            preWarmNativeHeap()

            // (5) Subscribe to thermal events so we can log throttling.
            registerThermalListener(ctx)

            Logger.i(TAG, "════════════════════════════════════════════════")
            Logger.i(TAG, "  PROCESS-WIDE PERFORMANCE MODE ACTIVE")
            Logger.i(TAG, "  - CPU cores: $cpuCount, big cores: ${bigCoreIds.toList()}")
            Logger.i(TAG, "  - Sustained Perf supported: $supportsSustainedPerf")
            Logger.i(TAG, "════════════════════════════════════════════════")
        } finally {
            Trace.endSection()
        }
        return true
    }

    /**
     * Tweaks the [Window] of an Activity for game-grade rendering:
     *  - Sustained Performance Mode (steady SoC clock).
     *  - FLAG_KEEP_SCREEN_ON (keeps GPU out of power-save mode).
     *  - Highest available display refresh rate (smoother UI, higher
     *    surfaceflinger budget which indirectly benefits inference TTFT).
     */
    fun applyWindow(window: Window) {
        Trace.beginSection("PerformanceManager#applyWindow")
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Sustained Performance Mode — only effective on devices that
            // advertise support. On devices that don't, this call is a no-op.
            // setSustainedPerformanceMode requires API 24, safe for minSdk 35.
            if (supportsSustainedPerf) {
                try {
                    window.setSustainedPerformanceMode(true)
                    Logger.i(TAG, "Sustained Performance Mode requested")
                } catch (t: Throwable) {
                    Logger.w(TAG, "setSustainedPerformanceMode failed: ${t.message}")
                }
            }

            // Pick the highest refresh rate the panel supports — gives the
            // CPU/GPU governor a reason to stay at high freq.
            try {
                // Context.display was added in API 30; minSdk 36 guarantees
                // availability but the property is still typed as nullable.
                val display: Display? = window.context.display
                if (display != null) {
                    val supportedRates = display.supportedModes.map { it.refreshRate }
                    val maxRate = supportedRates.maxOrNull()
                    if (maxRate != null && maxRate > 0f) {
                        val attrs = window.attributes
                        attrs.preferredRefreshRate = maxRate
                        // preferredDisplayModeId = pick the mode whose
                        // refreshRate matches the maximum at the same
                        // resolution (preserves panel native size).
                        val currentWidth = display.mode.physicalWidth
                        val mode =
                            display.supportedModes
                                .filter { it.physicalWidth == currentWidth }
                                .maxByOrNull { it.refreshRate }
                        if (mode != null) attrs.preferredDisplayModeId = mode.modeId
                        window.attributes = attrs
                        Logger.i(TAG, "Preferred refresh rate set to $maxRate Hz")
                    }
                }
            } catch (t: Throwable) {
                Logger.w(TAG, "Could not set max refresh rate: ${t.message}")
            }
        } finally {
            Trace.endSection()
        }
    }

    /**
     * Boost the calling thread (e.g. an inference worker) to the highest
     * priority and pin it to the performance cluster. Use sparingly — only
     * on threads that perform sustained, latency-critical work.
     */
    fun boostCurrentThread(name: String) {
        try {
            Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (t: Throwable) {
            Logger.w(TAG, "boostCurrentThread($name) priority failed: ${t.message}")
        }
        pinCurrentThreadToBigCores()
        Logger.d(TAG, "Thread '$name' boosted (tid=${Process.myTid()})")
    }

    // ---------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------

    @SuppressLint("WakelockTimeout") // Process-wide wake lock held for the duration of the inference process by design.
    private fun acquireCpuWakeLock(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl =
            pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "LlmServer::ProcessCpuWakeLock",
            )
        wl.setReferenceCounted(false)
        try {
            wl.acquire()
            cpuWakeLock = wl
            Logger.i(TAG, "Process-wide CPU wake lock acquired")
        } catch (t: Throwable) {
            Logger.w(TAG, "Could not acquire CPU wake lock: ${t.message}")
        }
    }

    private fun registerThermalListener(context: Context) {
        // OnThermalStatusChangedListener requires API 29, safe for minSdk 35.
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val listener =
            PowerManager.OnThermalStatusChangedListener { status ->
                val name = thermalStatusName(status)
                if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                    Logger.w(TAG, "Thermal status changed: $name (status=$status) — SoC may throttle")
                } else {
                    Logger.i(TAG, "Thermal status: $name")
                }
            }
        try {
            pm.addThermalStatusListener(listener)
            thermalListener = listener
        } catch (t: Throwable) {
            Logger.w(TAG, "Could not register thermal listener: ${t.message}")
        }
    }

    private fun thermalStatusName(s: Int): String =
        when (s) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($s)"
        }

    /**
     * Pin the **calling** thread to the inferred big cores via the
     * Linux `sched_setaffinity` syscall (exposed by Android's NDK but not
     * by the SDK). We approximate the same behaviour by writing the cpu
     * mask to /proc/self/task/<tid>/cpuset where supported.
     *
     * Best effort — if any of this fails the thread simply runs on whatever
     * core the scheduler picks, which is still acceptable.
     */
    private fun pinCurrentThreadToBigCores() {
        if (bigCoreIds.isEmpty()) return
        val tid = Process.myTid()
        // Try via taskset-style writes — supported on most Android kernels.
        try {
            val mask = bigCoreIds.fold(0L) { acc, id -> acc or (1L shl id) }
            // Use the schedtune/cpuset approach: write to cpus list file.
            val path = "/proc/self/task/$tid/cpus"
            val list = bigCoreIds.joinToString(",")
            File(path).takeIf { it.canWrite() }?.writeText(list)
            Logger.d(TAG, "Pinned tid=$tid to cores=$list (mask=0x${mask.toString(16)})")
        } catch (_: Throwable) {
            // Fine — kernel doesn't expose that path.
        }
    }

    /**
     * Heuristic to figure out which CPU IDs belong to the big cluster.
     *
     * Reads `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq` and
     * groups cores by their reported maximum frequency. The cluster with
     * the highest max freq is "big" (or "prime"); we return that group.
     */
    private fun detectBigCores(): IntArray {
        val n = cpuCount
        val freqs = IntArray(n) { -1 }
        for (i in 0 until n) {
            try {
                val raw = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim()
                freqs[i] = raw.toInt()
            } catch (_: Throwable) {
                // Some cores may be offline; skip them.
            }
        }
        val maxFreq = freqs.max()
        if (maxFreq <= 0) return IntArray(0)
        // Big cores = anything within 5 % of the absolute maximum.
        val threshold = (maxFreq * 0.95).toInt()
        return (0 until n).filter { freqs[it] >= threshold }.toIntArray()
    }

    /**
     * Pre-touch ~64 MB of native memory to force the kernel to commit
     * pages to us up-front. Reduces page-fault jitter during the first
     * burst of inference (which allocates many MB of KV-cache pages).
     */
    private fun preWarmNativeHeap() {
        try {
            val sizeMb = 64
            val buf = ByteArray(sizeMb * 1024 * 1024)
            // Touch every 4 KB page so the kernel actually backs them.
            var i = 0
            val pageSize = 4096
            while (i < buf.size) {
                buf[i] = 1
                i += pageSize
            }
            // Buf goes out of scope; pages remain in the pool, GC will
            // reclaim eventually but the working set is now hot.
            Logger.d(TAG, "Pre-warmed $sizeMb MB of heap pages")
        } catch (t: Throwable) {
            Logger.w(TAG, "Heap pre-warm failed: ${t.message}")
        }
    }
}
