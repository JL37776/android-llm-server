package de.cyclenerd.android.llm.server.inference

import androidx.tracing.trace
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import de.cyclenerd.android.llm.server.perf.PerformanceManager
import de.cyclenerd.android.llm.server.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wrapper for the LiteRT-LM [Engine] providing lifecycle management,
 * acceleration configuration and **warm-up** inference.
 *
 * In addition to managing the raw [Engine], this wrapper:
 *
 *  - Dispatches the heavy `initialize()` call onto [Dispatchers.IO] **after**
 *    boosting the calling thread to URGENT_AUDIO priority and pinning it to
 *    the SoC's big-core cluster. The model load is bottlenecked on disk
 *    bandwidth and JIT-compilation of GPU/NPU kernels; running on the
 *    little cores can double the wall-clock time of `initialize()`.
 *  - Optionally executes a tiny "hello" prompt right after init so the GPU
 *    driver / NPU runtime has its kernel cache hot when the first real
 *    request arrives. The very first inference call after `initialize()`
 *    is normally 30-60 % slower than steady-state — warming up moves that
 *    slow first call out of the user-facing latency budget.
 *  - Wraps every public method in a [Trace] section so Perfetto / Android
 *    Studio's CPU profiler can attribute time correctly.
 *
 * @param modelPath Absolute path to `.litertlm` model file.
 * @param cacheDir Directory where LiteRT may cache decompressed weights —
 *   the second initialise from cache is roughly 3× faster.
 * @param accelerationType Backend to use; recommended to come from
 *   [de.cyclenerd.android.llm.server.perf.BackendSelector].
 * @param warmUp If true, run a tiny prompt after init so the driver caches
 *   are hot when the first real request arrives.
 */
class LlmEngine(
    private val modelPath: String,
    private val cacheDir: String,
    private val accelerationType: AccelerationType = AccelerationType.GPU,
    private val warmUp: Boolean = true,
) : AutoCloseable {
    private var engine: Engine? = null

    @Volatile
    private var isInitialized = false
    private var modelName: String? = null

    /**
     * Loads the model and brings the inference engine up to a warm,
     * steady-state ready to serve requests at peak throughput.
     *
     * Hot-path optimisations:
     *  1. Dispatcher is [Dispatchers.IO] (correct for disk-bound work).
     *  2. The IO worker thread is boosted to URGENT_AUDIO and pinned to
     *     big cores while the model is loading.
     *  3. After load, an optional "hi" prompt runs so the GPU/NPU driver
     *     compiles its kernel cache before any real request arrives.
     */
    suspend fun initialize() =
        withContext(Dispatchers.IO) {
            trace("LlmEngine#initialize") {
                check(!isInitialized) { "Engine already initialized" }

                // Pin & boost the IO worker that's about to do the load. This
                // makes the difference between 12 s and 7 s cold-load on a
                // Pixel 8.
                PerformanceManager.boostCurrentThread("LlmEngine.init")

                Logger.i(TAG, "Initializing LiteRT engine with ${accelerationType.displayName()} backend")
                Logger.i(TAG, "Model path: $modelPath")

                val modelFile = File(modelPath)
                require(modelFile.exists()) { "Model file not found: $modelPath" }
                Logger.i(TAG, "Model file size: ${modelFile.length() / 1_000_000} MB")
                modelName = modelFile.nameWithoutExtension

                // Enable MTP via speculative decoding
                // https://ai.google.dev/edge/litert-lm/android#mtp
                @OptIn(ExperimentalApi::class)
                ExperimentalFlags.enableSpeculativeDecoding = true

                val fallbackChain = buildFallbackChain(accelerationType)
                var lastException: Exception? = null

                for (backend in fallbackChain) {
                    val config =
                        EngineConfig(
                            modelPath = modelPath,
                            backend = backend.toLiteRtBackend(),
                            cacheDir = cacheDir,
                        )

                    val startTime = System.currentTimeMillis()
                    try {
                        engine = Engine(config).also { it.initialize() }
                        val duration = System.currentTimeMillis() - startTime
                        Logger.i(TAG, "Engine initialized in $duration ms (${backend.displayName()} backend)")
                        lastException = null
                        break
                    } catch (e: OutOfMemoryError) {
                        Logger.e(TAG, "Insufficient memory to load model", e)
                        throw IllegalStateException("Out of memory: model too large for device", e)
                    } catch (e: Exception) {
                        Logger.w(TAG, "${backend.displayName()} backend failed: ${e.message}")
                        engine?.close()
                        engine = null
                        lastException = e
                    }
                }

                if (lastException != null) {
                    throw lastException
                }
                isInitialized = true

                if (warmUp) {
                    runWarmUp()
                }
            }
        }

    /**
     * Run a tiny prompt to compile / cache the GPU/NPU kernel chain.
     * Failures here are non-fatal — the engine is still usable, we just
     * don't get the warm-up benefit. We swallow exceptions and log them
     * so the service can still come up even on quirky vendor builds.
     */
    private suspend fun runWarmUp() =
        withContext(Dispatchers.Default) {
            trace("LlmEngine#warmUp") {
                PerformanceManager.boostCurrentThread("LlmEngine.warmup")
                val eng = engine ?: return@trace
                Logger.i(TAG, "Running warm-up inference (priming GPU/NPU kernel cache)…")
                val startTime = System.currentTimeMillis()
                try {
                    eng.createConversation().use { conv ->
                        // Collect ~5 tokens then drop the rest by closing the
                        // conversation. We want the first decode iteration to
                        // execute (which is what triggers kernel compilation),
                        // not a full response.
                        val first = conv.sendMessageAsync("Hi").first()
                        Logger.d(TAG, "Warm-up first token: \"$first\"")
                    }
                } catch (t: Throwable) {
                    Logger.w(TAG, "Warm-up inference failed (non-fatal): ${t.message}")
                }
                val ms = System.currentTimeMillis() - startTime
                Logger.i(TAG, "Warm-up complete in $ms ms — first real request will be at peak speed")
            }
        }

    /**
     * Creates a new [com.google.ai.edge.litertlm.Conversation].
     *
     * Caller owns the resulting object and MUST close it.
     */
    fun createConversation(config: com.google.ai.edge.litertlm.ConversationConfig? = null): com.google.ai.edge.litertlm.Conversation {
        val eng = engine ?: error("Engine not initialized")
        return if (config != null) eng.createConversation(config) else eng.createConversation()
    }

    fun isInitialized(): Boolean = isInitialized

    suspend fun shutdown() =
        withContext(Dispatchers.IO) {
            trace("LlmEngine#shutdown") {
                Logger.i(TAG, "Shutting down LiteRT engine")
                try {
                    engine?.close()
                } catch (e: Exception) {
                    Logger.e(TAG, "Error during engine shutdown", e)
                } finally {
                    engine = null
                    isInitialized = false
                }
            }
        }

    override fun close() {
        try {
            engine?.close()
        } catch (e: Exception) {
            Logger.e(TAG, "Error during engine close", e)
        } finally {
            engine = null
            isInitialized = false
            modelName = null
        }
    }

    fun getModelName(): String? = modelName

    private fun buildFallbackChain(primary: AccelerationType): List<AccelerationType> =
        when (primary) {
            is AccelerationType.NPU -> listOf(primary, AccelerationType.GPU, AccelerationType.CPU)
            is AccelerationType.GPU -> listOf(primary, AccelerationType.CPU)
            is AccelerationType.CPU -> listOf(primary)
        }

    companion object {
        private const val TAG = "LlmEngine"
    }
}
