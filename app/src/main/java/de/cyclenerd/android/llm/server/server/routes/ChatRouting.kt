package de.cyclenerd.android.llm.server.server.routes

import androidx.tracing.trace
import de.cyclenerd.android.llm.server.inference.ConversationManager
import de.cyclenerd.android.llm.server.inference.MetricsCollector
import de.cyclenerd.android.llm.server.inference.PerformanceMetrics
import de.cyclenerd.android.llm.server.inference.StreamingHandler
import de.cyclenerd.android.llm.server.inference.ToolCallParser
import de.cyclenerd.android.llm.server.inference.withMetrics
import de.cyclenerd.android.llm.server.server.MetricsAttributeKey
import de.cyclenerd.android.llm.server.server.models.ChatCompletionChunk
import de.cyclenerd.android.llm.server.server.models.ChatCompletionRequest
import de.cyclenerd.android.llm.server.server.models.ChatCompletionResponse
import de.cyclenerd.android.llm.server.server.models.ChatMessage
import de.cyclenerd.android.llm.server.server.models.Choice
import de.cyclenerd.android.llm.server.server.models.ChoiceDelta
import de.cyclenerd.android.llm.server.server.models.ErrorDetail
import de.cyclenerd.android.llm.server.server.models.ErrorResponse
import de.cyclenerd.android.llm.server.server.models.FinishReason
import de.cyclenerd.android.llm.server.server.models.MessageDelta
import de.cyclenerd.android.llm.server.server.models.StreamingFunctionCall
import de.cyclenerd.android.llm.server.server.models.StreamingToolCall
import de.cyclenerd.android.llm.server.server.models.Usage
import de.cyclenerd.android.llm.server.utils.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: Long,
)

private val sseJson =
    Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

fun Application.configureChatRoutes(
    conversationManager: ConversationManager,
    onMetrics: ((PerformanceMetrics) -> Unit)? = null,
) {
    routing {
        post("/v1/chat/completions") {
            handleChatCompletion(conversationManager, call, onMetrics)
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, HealthResponse("healthy", System.currentTimeMillis()))
        }

        get("/") {
            call.respondText("Local LLM Server", ContentType.Text.Plain)
        }
    }
}

private suspend fun handleChatCompletion(
    conversationManager: ConversationManager,
    call: ApplicationCall,
    onMetrics: ((PerformanceMetrics) -> Unit)?,
) {
    trace("handleChatCompletion") {
        try {
            val request = call.receive<ChatCompletionRequest>()
            Logger.i(TAG, "Chat completion: stream=${request.stream}, tools=${request.tools?.size ?: 0}")

            if (request.stream) {
                handleStreamingRequest(conversationManager, request, call, onMetrics)
            } else {
                handleNonStreamingRequest(conversationManager, request, call, onMetrics)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Error handling chat completion", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    ErrorDetail(
                        message = e.message ?: "Internal server error",
                        type = "internal_error",
                    ),
                ),
            )
        }
    }
}

private suspend fun handleNonStreamingRequest(
    conversationManager: ConversationManager,
    request: ChatCompletionRequest,
    call: ApplicationCall,
    onMetrics: ((PerformanceMetrics) -> Unit)?,
) {
    val metricsCollector = MetricsCollector()

    conversationManager.withConversation(request) { conversation, userPrompt ->
        val promptTokens = estimateTokenCount(userPrompt)

        var tokenFlow = StreamingHandler.streamResponse(conversation, userPrompt)
        if (request.maxTokens != null && request.maxTokens > 0) {
            tokenFlow = tokenFlow.take(request.maxTokens)
        }

        val sb = StringBuilder(2048)
        tokenFlow.withMetrics(metricsCollector).toList().forEach { sb.append(it) }
        var responseText = sb.toString()

        responseText = applyStopSequences(responseText, parseStopSequences(request.stop))

        val parseResult = ToolCallParser.parseToolCalls(responseText, request.tools)
        val finishReason = if (parseResult.hasToolCalls) FinishReason.TOOL_CALLS else FinishReason.STOP

        val metrics = metricsCollector.onComplete(promptTokens)
        call.attributes.put(MetricsAttributeKey, metrics)
        onMetrics?.invoke(metrics)

        val response =
            ChatCompletionResponse(
                id = "chatcmpl-${UUID.randomUUID()}",
                created = System.currentTimeMillis() / 1000,
                model = request.model,
                choices =
                    listOf(
                        Choice(
                            index = 0,
                            message =
                                ChatMessage(
                                    role = "assistant",
                                    content = parseResult.content,
                                    toolCalls = parseResult.toolCalls,
                                ),
                            finishReason = finishReason,
                        ),
                    ),
                usage =
                    Usage(
                        promptTokens = promptTokens,
                        completionTokens = metrics.totalTokensGenerated,
                        totalTokens = promptTokens + metrics.totalTokensGenerated,
                    ),
            )

        call.respond(HttpStatusCode.OK, response)
        Logger.i(TAG, "Non-streaming: ${metrics.totalTokensGenerated} tokens, tools=${parseResult.hasToolCalls}")
    }
}

private suspend fun handleStreamingRequest(
    conversationManager: ConversationManager,
    request: ChatCompletionRequest,
    call: ApplicationCall,
    onMetrics: ((PerformanceMetrics) -> Unit)?,
) {
    val metricsCollector = MetricsCollector()
    val hasTools = !request.tools.isNullOrEmpty()

    conversationManager.withConversation(request) { conversation, userPrompt ->
        val promptTokens = estimateTokenCount(userPrompt)
        val requestId = "chatcmpl-${UUID.randomUUID()}"
        val timestamp = System.currentTimeMillis() / 1000
        val stopSequences = parseStopSequences(request.stop)

        call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Connection, "keep-alive")
        call.response.header("X-Accel-Buffering", "no")

        if (hasTools) {
            handleBufferedStreaming(
                conversation, userPrompt, request, call, metricsCollector,
                promptTokens, requestId, timestamp, stopSequences, onMetrics,
            )
        } else {
            handleDirectStreaming(
                conversation, userPrompt, request, call, metricsCollector,
                promptTokens, requestId, timestamp, stopSequences, onMetrics,
            )
        }
    }
}

private suspend fun handleBufferedStreaming(
    conversation: com.google.ai.edge.litertlm.Conversation,
    userPrompt: String,
    request: ChatCompletionRequest,
    call: ApplicationCall,
    metricsCollector: MetricsCollector,
    promptTokens: Int,
    requestId: String,
    timestamp: Long,
    stopSequences: List<String>,
    onMetrics: ((PerformanceMetrics) -> Unit)?,
) {
    var tokenFlow = StreamingHandler.streamResponse(conversation, userPrompt)
    if (request.maxTokens != null && request.maxTokens > 0) {
        tokenFlow = tokenFlow.take(request.maxTokens)
    }

    val sb = StringBuilder(2048)
    tokenFlow.withMetrics(metricsCollector).toList().forEach { sb.append(it) }
    var responseText = sb.toString()
    responseText = applyStopSequences(responseText, stopSequences)

    val parseResult = ToolCallParser.parseToolCalls(responseText, request.tools)
    val finishReason = if (parseResult.hasToolCalls) FinishReason.TOOL_CALLS else FinishReason.STOP

    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        writeSSEChunk(requestId, timestamp, request.model, MessageDelta(role = "assistant"), null)

        if (!parseResult.content.isNullOrEmpty()) {
            writeSSEChunk(requestId, timestamp, request.model, MessageDelta(content = parseResult.content), null)
        }

        if (parseResult.hasToolCalls) {
            for ((idx, tc) in parseResult.toolCalls!!.withIndex()) {
                writeSSEChunk(
                    requestId, timestamp, request.model,
                    MessageDelta(
                        toolCalls =
                            listOf(
                                StreamingToolCall(
                                    index = idx,
                                    id = tc.id,
                                    type = tc.type,
                                    function =
                                        StreamingFunctionCall(
                                            name = tc.function.name,
                                            arguments = tc.function.arguments,
                                        ),
                                ),
                            ),
                    ),
                    null,
                )
            }
        }

        writeSSEChunk(requestId, timestamp, request.model, MessageDelta(), finishReason)
        write("data: [DONE]\n\n")
        flush()

        val metrics = metricsCollector.onComplete(promptTokens)
        call.attributes.put(MetricsAttributeKey, metrics)
        onMetrics?.invoke(metrics)
        Logger.i(TAG, "Buffered streaming: ${metrics.totalTokensGenerated} tokens, tools=${parseResult.hasToolCalls}")
    }
}

private suspend fun handleDirectStreaming(
    conversation: com.google.ai.edge.litertlm.Conversation,
    userPrompt: String,
    request: ChatCompletionRequest,
    call: ApplicationCall,
    metricsCollector: MetricsCollector,
    promptTokens: Int,
    requestId: String,
    timestamp: Long,
    stopSequences: List<String>,
    onMetrics: ((PerformanceMetrics) -> Unit)?,
) {
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        var isFirst = true
        val accumulated = StringBuilder()
        var stopped = false

        var tokenFlow = StreamingHandler.streamResponse(conversation, userPrompt)
        if (request.maxTokens != null && request.maxTokens > 0) {
            tokenFlow = tokenFlow.take(request.maxTokens)
        }

        tokenFlow
            .withMetrics(metricsCollector)
            .catch { e ->
                Logger.e(TAG, "Error during streaming", e)
                val errorChunk = createErrorChunk(requestId, timestamp, request.model, e.message)
                write("data: ${sseJson.encodeToString(errorChunk)}\n\n")
                flush()
            }.collect { token ->
                if (stopped) return@collect

                if (stopSequences.isNotEmpty()) {
                    accumulated.append(token)
                    val fullText = accumulated.toString()
                    var stopIdx = -1
                    for (stop in stopSequences) {
                        val idx = fullText.indexOf(stop)
                        if (idx in 0 until (stopIdx.takeIf { it >= 0 } ?: Int.MAX_VALUE)) {
                            stopIdx = idx
                        }
                    }
                    if (stopIdx >= 0) {
                        val alreadyEmitted = fullText.length - token.length
                        if (stopIdx > alreadyEmitted) {
                            val partial = fullText.substring(alreadyEmitted, stopIdx)
                            writeSSEChunk(
                                requestId, timestamp, request.model,
                                MessageDelta(role = if (isFirst) "assistant" else null, content = partial),
                                null,
                            )
                            isFirst = false
                        }
                        stopped = true
                        return@collect
                    }
                }

                writeSSEChunk(
                    requestId, timestamp, request.model,
                    MessageDelta(role = if (isFirst) "assistant" else null, content = token),
                    null,
                )
                isFirst = false
            }

        writeSSEChunk(requestId, timestamp, request.model, MessageDelta(), FinishReason.STOP)
        write("data: [DONE]\n\n")
        flush()

        val metrics = metricsCollector.onComplete(promptTokens)
        call.attributes.put(MetricsAttributeKey, metrics)
        onMetrics?.invoke(metrics)
        Logger.i(TAG, "Streaming: ${metrics.totalTokensGenerated} tokens in ${metrics.totalTimeMs} ms")
    }
}

private fun java.io.Writer.writeSSEChunk(
    id: String,
    created: Long,
    model: String,
    delta: MessageDelta,
    finishReason: FinishReason?,
) {
    val chunk =
        ChatCompletionChunk(
            id = id,
            created = created,
            model = model,
            choices =
                listOf(
                    ChoiceDelta(
                        index = 0,
                        delta = delta,
                        finishReason = finishReason,
                    ),
                ),
        )
    write("data: ${sseJson.encodeToString(chunk)}\n\n")
    flush()
}

private fun createErrorChunk(
    id: String,
    created: Long,
    model: String,
    errorMessage: String?,
): ChatCompletionChunk =
    ChatCompletionChunk(
        id = id,
        created = created,
        model = model,
        choices =
            listOf(
                ChoiceDelta(
                    index = 0,
                    delta = MessageDelta(content = "[ERROR: $errorMessage]"),
                    finishReason = FinishReason.ERROR,
                ),
            ),
    )

private fun parseStopSequences(stop: kotlinx.serialization.json.JsonElement?): List<String> {
    if (stop == null) return emptyList()
    return when (stop) {
        is JsonPrimitive -> if (stop.isString) listOf(stop.content) else emptyList()
        is JsonArray ->
            stop.mapNotNull { elem ->
                (elem as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        else -> emptyList()
    }
}

private fun applyStopSequences(
    text: String,
    stopSequences: List<String>,
): String {
    if (stopSequences.isEmpty()) return text
    var minIdx = text.length
    for (stop in stopSequences) {
        val idx = text.indexOf(stop)
        if (idx in 0 until minIdx) minIdx = idx
    }
    return if (minIdx < text.length) text.substring(0, minIdx) else text
}

private fun estimateTokenCount(text: String): Int = (text.length / 4).coerceAtLeast(1)

private const val TAG = "ChatRoutes"
