package com.maderskitech.llmcommit

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.Serializable
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

class LlmService {
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun generateCommitMessage(address: String, modelName: String, diff: String): Result<CommitMessage> = runCatching {
        val model = modelName.ifBlank { "local-model" }

        val payload = buildJsonObject {
            put("model", model)
            put("temperature", JsonPrimitive(0.3))
            put("messages", buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put(
                            "content",
                            """
                            You are a commit message generator. Analyze the provided git diff and write a commit message.

                            Reply with EXACTLY two parts and nothing else:
                            1. A short imperative commit summary under 72 characters on the first line.
                            2. A detailed description after a blank line using '-' bullets.

                            Do not use markdown fences or JSON unless explicitly requested.
                            """.trimIndent()
                        )
                    }
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", "Generate a commit message for this diff:\n\n$diff")
                    }
                )
            })
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${address.trimEnd('/')}/chat/completions"))
            .timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(JsonObject.serializer(), payload)))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("LLM API error ${response.statusCode()}: ${response.body()}")
        }

        val parsed = json.parseToJsonElement(response.body()).jsonObject
        val content = parsed["choices"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?: error("LLM returned an empty response: ${response.body()}")

        parseResponse(content)
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
}
