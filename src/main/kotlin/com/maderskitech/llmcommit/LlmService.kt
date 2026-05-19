package com.maderskitech.llmcommit

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.ceil
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class CommitMessage(val summary: String, val description: String)

internal data class ModelContextWindow(val tokens: Int)

internal data class ModelPromptBudget(
    val usableInputTokens: Int,
    val attempt: Int,
)

class LlmService(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val providerContextCache = mutableMapOf<String, ModelContextWindow>()

    fun generateCommitMessage(address: String, modelName: String, diff: String): Result<CommitMessage> = runCatching {
        val model = modelName.ifBlank { "local-model" }
        val contextWindow = resolveModelContextWindow(address, model)
        val content = sendChatWithRetries(
            address = address,
            model = model,
            contextWindow = contextWindow,
        ) { budget ->
            val promptDiff = PromptCompactor.compactDiffToTokenBudget(diff, budget.usableInputTokens)
            buildMessages(
                systemPrompt =
                    """
                    You are a commit message generator. Analyze the provided git diff and write a commit message.

                    Reply with EXACTLY two parts and nothing else:
                    1. A short imperative commit summary under 72 characters on the first line.
                    2. A detailed description after a blank line using '-' bullets.

                    Do not use reasoning tokens, chain-of-thought, or explain your thinking.
                    Do not use markdown fences or JSON unless explicitly requested.
                    """.trimIndent(),
                userPrompt = "Generate a commit message for this diff:\n\n$promptDiff",
            )
        }

        parseResponse(content)
    }

    private fun sendChatWithRetries(
        address: String,
        model: String,
        contextWindow: ModelContextWindow,
        temperature: Double = 0.3,
        buildMessages: (ModelPromptBudget) -> JsonArray,
    ): String {
        var lastApiError = ""
        for ((index, ratio) in INPUT_BUDGET_ATTEMPT_RATIOS.withIndex()) {
            val budget = createPromptBudget(contextWindow, ratio, index + 1)
            val payload = buildJsonObject {
                put("model", model)
                put("temperature", JsonPrimitive(temperature))
                put("messages", buildMessages(budget))
            }

            val request = HttpRequest.newBuilder()
                .uri(URI.create("${address.trimEnd('/')}/chat/completions"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), payload)))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                return extractContent(response.body())
            }

            lastApiError = buildApiError(response.statusCode(), response.body())
            if (!isContextOverflowError(response.statusCode(), response.body())) {
                error(lastApiError)
            }
        }

        error(
            "LLM request exceeded the available context window (${contextWindow.tokens} tokens) " +
                "after ${INPUT_BUDGET_ATTEMPT_RATIOS.size} attempt(s). Last error: $lastApiError"
        )
    }

    private fun extractContent(body: String): String {
        val parsed = json.parseToJsonElement(body).jsonObject
        return parsed["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?: error("LLM returned an empty response: $body")
    }

    private fun buildMessages(systemPrompt: String, userPrompt: String): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            }
        )
        add(
            buildJsonObject {
                put("role", "user")
                put("content", userPrompt)
            }
        )
    }

    private fun resolveModelContextWindow(address: String, model: String): ModelContextWindow {
        val cacheKey = "${address.trimEnd('/')}\n$model"
        providerContextCache[cacheKey]?.let { return it }

        val resolved = discoverProviderContextWindow(address, model)
            ?: ModelContextWindow(DEFAULT_CONTEXT_WINDOW_TOKENS)
        providerContextCache[cacheKey] = resolved
        return resolved
    }

    private fun discoverProviderContextWindow(address: String, model: String): ModelContextWindow? {
        val base = address.trimEnd('/')
        val lmStudioEndpoints = if (base.endsWith("/v1")) {
            val root = base.dropLast(3)
            listOf("$root/api/v1/models/$model", "$root/api/v0/models/$model")
        } else emptyList()
        val endpoints = listOf("$base/models", "$base/models/$model") + lmStudioEndpoints

        endpoints.forEach { endpoint ->
            runCatching {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) return@runCatching null
                val root = json.parseToJsonElement(response.body())
                extractContextWindowTokens(root, model)
                    ?.takeIf { it >= MIN_CONTEXT_WINDOW_TOKENS }
                    ?.let(::ModelContextWindow)
            }.getOrNull()?.let { return it }
        }

        return null
    }

    private fun extractContextWindowTokens(root: JsonElement, model: String): Int? {
        val candidates = mutableListOf<JsonElement>()
        if (root is JsonObject) {
            val data = root["data"] as? JsonArray
            data?.firstOrNull { element ->
                (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull() == model
            }?.let { candidates += it }
        }
        candidates += root

        return candidates.asSequence()
            .mapNotNull(::findContextWindowTokens)
            .firstOrNull()
    }

    private fun findContextWindowTokens(element: JsonElement): Int? = when (element) {
        is JsonPrimitive -> element.content.toIntOrNull()?.takeIf { it >= MIN_CONTEXT_WINDOW_TOKENS }
        is JsonArray -> element.asSequence().mapNotNull(::findContextWindowTokens).firstOrNull()
        is JsonObject -> {
            CONTEXT_WINDOW_KEYS.asSequence()
                .mapNotNull { key -> element[key]?.let(::findContextWindowTokens) }
                .firstOrNull()
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

    private fun createPromptBudget(
        contextWindow: ModelContextWindow,
        attemptRatio: Double,
        attempt: Int,
    ): ModelPromptBudget {
        val safetyBufferTokens = maxOf(MIN_SAFETY_BUFFER_TOKENS, contextWindow.tokens / 10)
        val baseInputBudget = (
            contextWindow.tokens - COMMIT_OUTPUT_RESERVE_TOKENS - PROMPT_OVERHEAD_TOKENS - safetyBufferTokens
            ).coerceAtLeast(MIN_INPUT_BUDGET_TOKENS)
        val usableInputTokens = (baseInputBudget * attemptRatio).toInt().coerceAtLeast(1)
        return ModelPromptBudget(usableInputTokens = usableInputTokens, attempt = attempt)
    }

    private fun isContextOverflowError(statusCode: Int, body: String): Boolean {
        val normalized = body.lowercase()
        return statusCode in setOf(400, 413, 422) && CONTEXT_OVERFLOW_MARKERS.any { it in normalized }
    }

    private fun buildApiError(statusCode: Int, body: String): String {
        val compactBody = body.replace(Regex("\\s+"), " ").trim().take(500)
        return if (compactBody.isBlank()) {
            "LLM API error $statusCode"
        } else {
            "LLM API error $statusCode: $compactBody"
        }
    }

    private fun parseResponse(content: String): CommitMessage {
        val cleaned = content
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (cleaned.startsWith("{")) {
            runCatching {
                val obj = json.parseToJsonElement(cleaned).jsonObject
                val summary = listOf("summary", "title", "subject")
                    .firstNotNullOfOrNull { key -> obj[key]?.asPlainText()?.takeIf(String::isNotBlank) }
                    .orEmpty()
                val description = listOf("description", "body", "details", "changes", "bullets")
                    .firstNotNullOfOrNull { key -> obj[key]?.asPlainText()?.takeIf(String::isNotBlank) }
                    .orEmpty()
                if (summary.isNotBlank()) {
                    return CommitMessage(summary = summary, description = description)
                }
            }
        }

        val lines = cleaned.lines()
        val summaryIndex = lines.indexOfFirst { it.isNotBlank() }
        if (summaryIndex < 0) return CommitMessage("Update project files", "")

        val summary = lines[summaryIndex].trim().removeSurrounding("\"")
        var description = lines
            .drop(summaryIndex + 1)
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()

        if (description.isBlank()) {
            val compact = summary.trim()
            listOf(" - ", " — ", ": ").forEach { separator ->
                if (compact.contains(separator)) {
                    val parts = compact.split(separator, limit = 2)
                    if (parts.size == 2) {
                        return CommitMessage(parts[0].trim(), "- ${parts[1].trim()}")
                    }
                }
            }
        }

        if (description.isNotBlank() && !description.lines().first().trim().startsWith("-")) {
            description = "- " + description.lines().joinToString("\n- ") { it.trim() }
        }

        return CommitMessage(summary = summary.ifBlank { "Update project files" }, description = description)
    }

    private fun kotlinx.serialization.json.JsonElement.asPlainText(): String = when (this) {
        is JsonPrimitive -> content
        is JsonObject -> values.joinToString("\n") { it.asPlainText() }
        is kotlinx.serialization.json.JsonArray -> joinToString("\n") { it.asPlainText() }
    }

    private companion object {
        private const val DEFAULT_CONTEXT_WINDOW_TOKENS = 8_192
        private const val MIN_CONTEXT_WINDOW_TOKENS = 256
        private const val MIN_INPUT_BUDGET_TOKENS = 1_024
        private const val MIN_SAFETY_BUFFER_TOKENS = 768
        private const val PROMPT_OVERHEAD_TOKENS = 512
        private const val COMMIT_OUTPUT_RESERVE_TOKENS = 700
        private val INPUT_BUDGET_ATTEMPT_RATIOS = listOf(1.0, 0.72, 0.5)
        private val CONTEXT_WINDOW_KEYS = listOf(
            "context_length",
            "context_window",
            "max_context_length",
            "max_input_tokens",
            "num_ctx",
            "n_ctx",
        )
        private val CONTEXT_OVERFLOW_MARKERS = listOf(
            "context limit",
            "context length",
            "maximum context length",
            "max context length",
            "too many tokens",
            "prompt is too long",
            "request too large",
            "exceeds context",
            "context window",
        )
    }
}

internal object PromptCompactor {
    private const val MIN_TRAILING_SECTION_CHARS = 600
    private const val CHARS_PER_TOKEN_ESTIMATE = 2.5

    fun compactDiffToTokenBudget(diff: String, maxTokens: Int): String {
        val maxChars = tokenBudgetToChars(maxTokens)
        val maxCharsPerSection = (maxChars / 3).coerceAtLeast(512)
        return compactDiff(diff, maxChars, maxCharsPerSection)
    }

    private fun compactDiff(
        diff: String,
        maxChars: Int,
        maxCharsPerSection: Int,
    ): String {
        if (diff.length <= maxChars) return diff

        val sections = splitDiffSections(diff)
        if (sections.size <= 1) {
            return truncateWithNotice(diff, "diff", maxChars)
        }

        val truncatedSections = sections.map { section ->
            if (section.length <= maxCharsPerSection) section else truncateSection(section, maxCharsPerSection)
        }

        val builder = StringBuilder()
        for (section in truncatedSections) {
            val separatorLength = if (builder.isEmpty()) 0 else 1
            if (builder.length + separatorLength + section.length > maxChars) break
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(section.trimEnd())
        }

        val compacted = if (builder.isNotEmpty()) builder.toString() else truncateSection(diff, maxChars)
        val includedSections = countIncludedSections(compacted)
        val notice = "[diff truncated to fit model context: ${diff.length} chars across ${sections.size}" +
            " file patch(es); sending ${compacted.length} chars across $includedSections patch(es)]\n\n"
        val contentBudget = maxChars - notice.length
        return if (contentBudget <= 0) notice.take(maxChars) else notice + compacted.take(contentBudget)
    }

    private fun splitDiffSections(diff: String): List<String> {
        val sections = mutableListOf<String>()
        val current = StringBuilder()
        diff.lineSequence().forEach { line ->
            if (line.trimStart().startsWith("diff --git ") && current.isNotEmpty()) {
                sections += current.toString().trimEnd()
                current.clear()
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) sections += current.toString().trimEnd()
        return sections.filter { it.isNotBlank() }
    }

    private fun truncateWithNotice(text: String, label: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val notice = "[${label.trim()} truncated to fit model context: ${text.length} chars -> ${maxChars} chars]\n\n"
        val usableChars = maxChars - notice.length
        if (usableChars <= 0) return notice.take(maxChars)
        return notice + truncateMiddle(text, usableChars)
    }

    private fun truncateSection(section: String, maxChars: Int): String {
        if (section.length <= maxChars) return section
        val hasHunk = section.lineSequence().any { it.trimStart().startsWith("@@") }
        if (!hasHunk) return truncateMiddle(section, maxChars)
        val header = section.lineSequence()
            .takeWhile { !it.trimStart().startsWith("@@") }
            .joinToString("\n")
            .trimEnd()
        val headerWithSpacing = if (header.isBlank()) "" else "$header\n"
        val remaining = maxChars - headerWithSpacing.length
        if (remaining <= 0) return headerWithSpacing.take(maxChars)
        val body = section.removePrefix(headerWithSpacing)
        return headerWithSpacing + truncateMiddle(body, remaining)
    }

    private fun truncateMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val marker = "\n... [truncated] ...\n"
        val remaining = maxChars - marker.length
        if (remaining <= 0) return text.take(maxChars)
        val head = remaining * 3 / 4
        val tail = remaining - head
        val targetTail = MIN_TRAILING_SECTION_CHARS.coerceAtMost(remaining / 2)
        val safeTail = if (tail >= targetTail) tail else targetTail
        val safeHead = (remaining - safeTail).coerceAtLeast(0)
        return text.take(safeHead) + marker + text.takeLast(safeTail)
    }

    private fun countIncludedSections(text: String): Int =
        text.lineSequence().count { it.trimStart().startsWith("diff --git ") }.coerceAtLeast(1)

    private fun tokenBudgetToChars(maxTokens: Int): Int =
        ceil(maxTokens * CHARS_PER_TOKEN_ESTIMATE).toInt().coerceAtLeast(256)
}
