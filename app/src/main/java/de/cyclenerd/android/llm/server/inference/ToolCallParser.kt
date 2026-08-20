package de.cyclenerd.android.llm.server.inference

import de.cyclenerd.android.llm.server.server.models.FunctionCallResult
import de.cyclenerd.android.llm.server.server.models.ToolCall
import de.cyclenerd.android.llm.server.server.models.ToolDefinition
import de.cyclenerd.android.llm.server.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

object ToolCallParser {
    private const val TAG = "ToolCallParser"

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val toolCallRegex = Regex(
        """<tool_call>\s*(\{.*?\})\s*</tool_call>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    fun buildToolSystemPrompt(
        tools: List<ToolDefinition>,
        existingSystem: String?,
    ): String {
        val sb = StringBuilder()
        if (!existingSystem.isNullOrBlank()) {
            sb.appendLine(existingSystem)
            sb.appendLine()
        }

        sb.appendLine("# Tools")
        sb.appendLine()
        sb.appendLine("You have access to the following tools. When you need to call a tool, output EXACTLY this format:")
        sb.appendLine()
        sb.appendLine("""<tool_call>""")
        sb.appendLine("""{"name": "tool_name", "arguments": {"param1": "value1"}}""")
        sb.appendLine("""</tool_call>""")
        sb.appendLine()
        sb.appendLine("You can call multiple tools by using multiple <tool_call> blocks.")
        sb.appendLine("If you don't need to call any tool, respond normally without <tool_call> tags.")
        sb.appendLine()
        sb.appendLine("Available tools:")
        sb.appendLine()

        for (tool in tools) {
            val fn = tool.function
            sb.appendLine("## ${fn.name}")
            if (!fn.description.isNullOrBlank()) {
                sb.appendLine("Description: ${fn.description}")
            }
            if (fn.parameters != null) {
                sb.appendLine("Parameters: ${fn.parameters}")
            }
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    fun buildJsonModePrompt(existingSystem: String?): String {
        val sb = StringBuilder()
        if (!existingSystem.isNullOrBlank()) {
            sb.appendLine(existingSystem)
            sb.appendLine()
        }
        sb.appendLine("You must respond with valid JSON only. Do not include any text outside the JSON object.")
        return sb.toString().trimEnd()
    }

    fun parseToolCalls(response: String, availableTools: List<ToolDefinition>?): ParseResult {
        if (availableTools.isNullOrEmpty()) {
            return ParseResult(content = response, toolCalls = null)
        }

        val matches = toolCallRegex.findAll(response).toList()
        if (matches.isEmpty()) {
            return ParseResult(content = response, toolCalls = null)
        }

        val toolNames = availableTools.map { it.function.name }.toSet()
        val parsed = mutableListOf<ToolCall>()

        for (match in matches) {
            try {
                val jsonStr = match.groupValues[1].trim()
                val obj = lenientJson.parseToJsonElement(jsonStr).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                if (name !in toolNames) {
                    Logger.w(TAG, "Model called unknown tool: $name, skipping")
                    continue
                }
                val args = obj["arguments"]?.toString() ?: "{}"
                parsed.add(
                    ToolCall(
                        id = "call_${UUID.randomUUID().toString().replace("-", "").take(24)}",
                        type = "function",
                        function = FunctionCallResult(name = name, arguments = args),
                    ),
                )
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to parse tool call: ${e.message}")
            }
        }

        if (parsed.isEmpty()) {
            return ParseResult(content = response, toolCalls = null)
        }

        val textOutsideToolCalls = toolCallRegex.replace(response, "").trim()
        val content = textOutsideToolCalls.ifBlank { null }

        return ParseResult(content = content, toolCalls = parsed)
    }

    fun formatToolResultForHistory(toolCallId: String?, name: String?, content: String?): String {
        val sb = StringBuilder()
        sb.append("[Tool Result")
        if (name != null) sb.append(" for $name")
        if (toolCallId != null) sb.append(" (id: $toolCallId)")
        sb.appendLine("]")
        sb.append(content ?: "")
        return sb.toString()
    }

    fun formatToolCallsForHistory(toolCalls: List<ToolCall>): String {
        val sb = StringBuilder()
        for (tc in toolCalls) {
            sb.appendLine("<tool_call>")
            sb.appendLine("""{"name": "${tc.function.name}", "arguments": ${tc.function.arguments}}""")
            sb.appendLine("</tool_call>")
        }
        return sb.toString().trimEnd()
    }

    data class ParseResult(
        val content: String?,
        val toolCalls: List<ToolCall>?,
    ) {
        val hasToolCalls: Boolean get() = !toolCalls.isNullOrEmpty()
    }
}
