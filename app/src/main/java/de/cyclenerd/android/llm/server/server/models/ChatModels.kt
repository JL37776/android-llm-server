package de.cyclenerd.android.llm.server.server.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("top_k") val topK: Int? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val stop: JsonElement? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

@Serializable
data class ResponseFormat(
    val type: String = "text",
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition,
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCallResult,
)

@Serializable
data class FunctionCallResult(
    val name: String,
    val arguments: String,
)

@Serializable
data class StreamingToolCall(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: StreamingFunctionCall? = null,
)

@Serializable
data class StreamingFunctionCall(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
enum class FinishReason {
    @SerialName("stop")
    STOP,

    @SerialName("length")
    LENGTH,

    @SerialName("content_filter")
    CONTENT_FILTER,

    @SerialName("tool_calls")
    TOOL_CALLS,

    @SerialName("error")
    ERROR,
}

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: FinishReason,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
)

@Serializable
data class MessageDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<StreamingToolCall>? = null,
)

@Serializable
data class ChoiceDelta(
    val index: Int,
    val delta: MessageDelta,
    @SerialName("finish_reason") val finishReason: FinishReason? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChoiceDelta>,
)

@Serializable
data class ErrorDetail(
    val message: String,
    val type: String,
    val code: String? = null,
    val param: String? = null,
)

@Serializable
data class ErrorResponse(
    val error: ErrorDetail,
)
