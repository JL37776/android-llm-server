package de.cyclenerd.android.llm.server.inference

import androidx.tracing.trace
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import de.cyclenerd.android.llm.server.perf.PerformanceManager
import de.cyclenerd.android.llm.server.server.models.ChatCompletionRequest
import de.cyclenerd.android.llm.server.utils.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversationManager(
    val engine: LlmEngine,
) {
    private val tag = "LLMServer:ConversationManager"
    private val conversationMutex = Mutex()

    @Volatile
    private var currentConversation: Conversation? = null

    @Volatile
    private var requestCount = 0

    private val defaultSamplerConfig =
        SamplerConfig(
            temperature = 1.0,
            topP = 0.95,
            topK = 64,
        )

    init {
        Logger.i(tag, "Initialized with default config: temp=1.0, topP=0.95, topK=64")
    }

    suspend fun <R> withConversation(
        request: ChatCompletionRequest,
        block: suspend (Conversation, String) -> R,
    ): R =
        trace("ConversationManager#withConversation") {
            conversationMutex.withLock {
                requestCount++
                val reqId = requestCount
                Logger.d(tag, "Processing request #$reqId with ${request.messages.size} messages")
                PerformanceManager.boostCurrentThread("ConversationManager.req$reqId")
                closeCurrentConversation()
                val (conversation, userPrompt) = createConversation(request)
                currentConversation = conversation
                try {
                    block(conversation, userPrompt)
                } finally {
                    closeCurrentConversation()
                }
            }
        }

    private fun createConversation(request: ChatCompletionRequest): Pair<Conversation, String> {
        val messages = request.messages

        var systemMessage = messages.firstOrNull { it.role == "system" }?.content

        if (!request.tools.isNullOrEmpty()) {
            systemMessage = ToolCallParser.buildToolSystemPrompt(request.tools, systemMessage)
        }
        if (request.responseFormat?.type == "json_object") {
            systemMessage = ToolCallParser.buildJsonModePrompt(systemMessage)
        }

        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        val userPrompt =
            if (lastUserIndex >= 0) {
                messages[lastUserIndex].content ?: ""
            } else {
                throw IllegalArgumentException("No user message found")
            }

        val initial = ArrayList<Message>(messages.size)
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg.role == "system") continue
            if (msg.role == "user" && i == lastUserIndex) continue
            when (msg.role) {
                "user" -> initial.add(Message.user(msg.content ?: ""))
                "assistant" -> {
                    val text =
                        if (!msg.toolCalls.isNullOrEmpty()) {
                            ToolCallParser.formatToolCallsForHistory(msg.toolCalls)
                        } else {
                            msg.content ?: ""
                        }
                    initial.add(Message.model(text))
                }
                "tool" -> {
                    initial.add(
                        Message.user(
                            ToolCallParser.formatToolResultForHistory(msg.toolCallId, msg.name, msg.content),
                        ),
                    )
                }
                else -> Logger.w(tag, "Unknown role: ${msg.role}, skipping")
            }
        }

        Logger.d(tag, "Creating conversation: systemMsg=${systemMessage != null}, history=${initial.size}, tools=${request.tools?.size ?: 0}")

        val sampler =
            if (request.temperature != null || request.topP != null || request.topK != null) {
                SamplerConfig(
                    temperature = request.temperature?.toDouble() ?: 1.0,
                    topP = request.topP?.toDouble() ?: 0.95,
                    topK = request.topK ?: 64,
                )
            } else {
                defaultSamplerConfig
            }

        val config =
            ConversationConfig(
                systemInstruction = systemMessage?.let { Contents.of(it) },
                initialMessages = initial,
                samplerConfig = sampler,
            )

        return Pair(engine.createConversation(config), userPrompt)
    }

    private fun closeCurrentConversation() {
        currentConversation?.let { conv ->
            try {
                conv.close()
                Logger.d(tag, "Conversation closed")
            } catch (e: Exception) {
                Logger.e(tag, "Error closing conversation: ${e.message}")
            }
        }
        currentConversation = null
    }

    fun clear() {
        Logger.i(tag, "Clearing conversation manager")
        closeCurrentConversation()
    }
}
