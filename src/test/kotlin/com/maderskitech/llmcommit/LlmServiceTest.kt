package com.maderskitech.llmcommit

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LlmServiceTest {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    @Test
    fun testConnection_sendsHelloPromptAndReturnsModelResponse() {
        var requestBody = ""
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { body, _ ->
                requestBody = body
                response(200, """{"choices":[{"message":{"content":"Hello received."}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.testConnection(server.baseUrl, "model")

            assertTrue(result.isSuccess)
            assertEquals("Hello received.", result.getOrThrow())
            assertContains(requestBody, "Hello. Reply with a brief confirmation")
        }
    }

    @Test
    fun generateCommitMessage_includesNoReasoningInstructionInPrompt() {
        var requestBody = ""
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { body, _ ->
                requestBody = body
                response(200, """{"choices":[{"message":{"content":"Keep prompts lean\n\n- Avoid extra reasoning output"}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/file.txt b/file.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            assertContains(requestBody, "Do not use reasoning tokens, chain-of-thought, or explain your thinking.")
        }
    }

    @Test
    fun generateCommitMessage_includesCompactionNotice_forOversizedDiff() {
        var requestBody = ""
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { body, _ ->
                requestBody = body
                response(200, """{"choices":[{"message":{"content":"Compact diff\n\n- Use prompt compaction"}}]}""")
            },
        )

        server.use {
            val diff = buildString {
                append("diff --git a/large.txt b/large.txt\n")
                append("@@ -1,1 +1,12000 @@\n")
                repeat(12_000) { index -> append("+line $index\n") }
            }

            val service = LlmService(client)
            val result = service.generateCommitMessage(server.baseUrl, "model", diff)

            assertTrue(result.isSuccess)
            assertContains(requestBody, "[diff truncated to fit model context:")
        }
    }

    @Test
    fun generateCommitMessage_setsMaxTokensToReservedCompletionBudget() {
        var requestBody = ""
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { body, _ ->
                requestBody = body
                response(200, """{"choices":[{"message":{"content":"Bound completion budget\n\n- Limit completion tokens explicitly"}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/file.txt b/file.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            val payload = Json.parseToJsonElement(requestBody).jsonObject
            assertEquals("700", payload.getValue("max_tokens").jsonPrimitive.content)
        }
    }

    @Test
    fun generateCommitMessage_parsesJsonPayloadReturnedAsContent() {
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { _, _ ->
                response(200, """{"choices":[{"message":{"content":"{\"summary\":\"Tighten prompt limits\",\"description\":\"- Compact oversized diffs\"}"}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/file.txt b/file.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            assertEquals("Tighten prompt limits", result.getOrThrow().summary)
            assertEquals("- Compact oversized diffs", result.getOrThrow().description)
        }
    }

    @Test
    fun generateCommitMessage_parsesSingleLineSummaryAndDescription() {
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { _, _ ->
                response(200, """{"choices":[{"message":{"content":"Tighten prompt limits - Compact oversized diffs"}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/file.txt b/file.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            assertEquals("Tighten prompt limits", result.getOrThrow().summary)
            assertEquals("- Compact oversized diffs", result.getOrThrow().description)
        }
    }

    @Test
    fun generateCommitMessage_retriesWithSmallerBudgetAfterContextOverflow() {
        val requestBodies = mutableListOf<String>()
        val completionCalls = AtomicInteger(0)
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":16384}]}""",
            completionHandler = { body, _ ->
                requestBodies += body
                when (completionCalls.incrementAndGet()) {
                    1 -> response(400, """{"error":"request exceeds context window"}""")
                    else -> response(200, """{"choices":[{"message":{"content":"Retry compact diff\n\n- Shrink prompt on overflow"}}]}""")
                }
            },
        )

        server.use {
            val diff = buildString {
                repeat(1_400) { fileIndex ->
                    append("diff --git a/src/File$fileIndex.kt b/src/File$fileIndex.kt\n")
                    append("@@ -1,1 +1,4 @@\n")
                    append("+line $fileIndex a with enough code content to consume prompt budget\n")
                    append("+line $fileIndex b with enough code content to consume prompt budget\n")
                    append("+line $fileIndex c with enough code content to consume prompt budget\n")
                    append("+line $fileIndex d with enough code content to consume prompt budget\n")
                }
            }

            val service = LlmService(client)
            val result = service.generateCommitMessage(server.baseUrl, "model", diff)

            assertTrue(result.isSuccess)
            assertEquals(2, completionCalls.get())
            assertTrue(requestBodies[1].length < requestBodies[0].length)
        }
    }

    @Test
    fun generateCommitMessage_respectsTinyContextWindowsWithoutInflatingInputBudget() {
        val completionCalls = AtomicInteger(0)
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":2048}]}""",
            completionHandler = { body, _ ->
                completionCalls.incrementAndGet()
                val payload = Json.parseToJsonElement(body).jsonObject
                val userMessage = payload.getValue("messages").jsonArray[1].jsonObject
                val userContent = userMessage.getValue("content").jsonPrimitive.content
                if (userContent.length > 1_000) {
                    response(400, """{"error":"request exceeds context window"}""")
                } else {
                    response(200, """{"choices":[{"message":{"content":"Fit tiny context\n\n- Keep prompts inside small model windows"}}]}""")
                }
            },
        )

        server.use {
            val diff = buildString {
                repeat(120) { fileIndex ->
                    append("diff --git a/file$fileIndex.txt b/file$fileIndex.txt\n")
                    append("@@ -1 +1 @@\n")
                    append("-line $fileIndex old content that keeps growing to pressure the prompt budget\n")
                    append("+line $fileIndex new content that keeps growing to pressure the prompt budget\n")
                }
            }

            val service = LlmService(client)
            val result = service.generateCommitMessage(server.baseUrl, "model", diff)

            assertTrue(result.isSuccess)
            assertEquals(1, completionCalls.get())
        }
    }

    @Test
    fun generateCommitMessage_shrinksEffectiveContextAfterOverflowOnDefaultWindow() {
        val completionCalls = AtomicInteger(0)
        val requestBodies = mutableListOf<String>()
        val server = testServer(
            modelsResponse = """{"data":[]}""",
            modelDetailResponse = response(404, ""),
            completionHandler = { body, _ ->
                requestBodies += body
                when (completionCalls.incrementAndGet()) {
                    1 -> response(400, """{"error":"request exceeds context window"}""")
                    else -> response(200, """{"choices":[{"message":{"content":"Adaptive shrink\n\n- Reduce context after overflow"}}]}""")
                }
            },
        )

        server.use {
            val diff = buildString {
                repeat(500) { fileIndex ->
                    append("diff --git a/file$fileIndex.txt b/file$fileIndex.txt\n")
                    append("@@ -1 +1 @@\n")
                    append("+content $fileIndex repeated to fill up the default context window budget\n")
                }
            }

            val service = LlmService(client)
            val result = service.generateCommitMessage(server.baseUrl, "model", diff)

            assertTrue(result.isSuccess)
            assertEquals(2, completionCalls.get())
            assertTrue(requestBodies[1].length < requestBodies[0].length)
        }
    }

    @Test
    fun generateCommitMessage_usesSoleListedModelWhenConfiguredModelNotFound() {
        val completionCalls = AtomicInteger(0)
        val server = testServer(
            modelsResponse = """{"data":[{"id":"actual-loaded-model","context_length":2048}]}""",
            completionHandler = { body, _ ->
                completionCalls.incrementAndGet()
                val payload = Json.parseToJsonElement(body).jsonObject
                val userContent = payload.getValue("messages").jsonArray[1].jsonObject
                    .getValue("content").jsonPrimitive.content
                if (userContent.length > 1_000) {
                    response(400, """{"error":"request exceeds context window"}""")
                } else {
                    response(200, """{"choices":[{"message":{"content":"Sole model match\n\n- Use single listed model context"}}]}""")
                }
            },
        )

        server.use {
            val diff = buildString {
                repeat(120) { fileIndex ->
                    append("diff --git a/file$fileIndex.txt b/file$fileIndex.txt\n")
                    append("@@ -1 +1 @@\n")
                    append("-line $fileIndex old content that keeps growing to pressure the prompt budget\n")
                    append("+line $fileIndex new content that keeps growing to pressure the prompt budget\n")
                }
            }

            val service = LlmService(client)
            val result = service.generateCommitMessage(server.baseUrl, "model", diff)

            assertTrue(result.isSuccess)
            assertEquals(1, completionCalls.get())
        }
    }

    @Test
    fun generateCommitMessage_discoversContextForModelWithPathSeparatorViaListEndpoint() {
        val completionCalls = AtomicInteger(0)
        val server = testServer(
            modelsResponse = """{"data":[{"id":"org/model","context_length":2048}]}""",
            completionHandler = { body, _ ->
                completionCalls.incrementAndGet()
                val payload = Json.parseToJsonElement(body).jsonObject
                val userContent = payload.getValue("messages").jsonArray[1].jsonObject
                    .getValue("content").jsonPrimitive.content
                if (userContent.length > 1_000) {
                    response(400, """{"error":"request exceeds context window"}""")
                } else {
                    response(200, """{"choices":[{"message":{"content":"Encode model path\n\n- Handle slash in model name"}}]}""")
                }
            },
        )

        server.use {
            val diff = buildString {
                repeat(120) { fileIndex ->
                    append("diff --git a/file$fileIndex.txt b/file$fileIndex.txt\n")
                    append("@@ -1 +1 @@\n")
                    append("-line $fileIndex old content that keeps growing to pressure the prompt budget\n")
                    append("+line $fileIndex new content that keeps growing to pressure the prompt budget\n")
                }
            }

            val service = LlmService(client)
            val result = service.generateCommitMessage(server.baseUrl, "org/model", diff)

            assertTrue(result.isSuccess)
            assertEquals(1, completionCalls.get())
        }
    }

    @Test
    fun generateCommitMessage_parsesLmStudioV1ModelLists() {
        val completionCalls = AtomicInteger(0)
        val server = testServer(
            modelsResponse = """{"data":[]}""",
            modelDetailResponse = response(404, """{"error":"not found"}"""),
            additionalContexts = mapOf(
                "/api/v1/models" to response(
                    200,
                    """{"models":[{"key":"model","config":{"context_length":2048}}]}""",
                ),
            ),
            completionHandler = { body, _ ->
                completionCalls.incrementAndGet()
                val payload = Json.parseToJsonElement(body).jsonObject
                val userMessage = payload.getValue("messages").jsonArray[1].jsonObject
                val userContent = userMessage.getValue("content").jsonPrimitive.content
                if (userContent.length > 1_000) {
                    response(400, """{"error":"request exceeds context window"}""")
                } else {
                    response(200, """{"choices":[{"message":{"content":"Use LM Studio context\n\n- Parse v1 model lists correctly"}}]}""")
                }
            },
        )

        server.use {
            val diff = buildString {
                repeat(120) { fileIndex ->
                    append("diff --git a/file$fileIndex.txt b/file$fileIndex.txt\n")
                    append("@@ -1 +1 @@\n")
                    append("-line $fileIndex old content that keeps growing to pressure the prompt budget\n")
                    append("+line $fileIndex new content that keeps growing to pressure the prompt budget\n")
                }
            }

            val service = LlmService(client)
            val result = service.generateCommitMessage(server.baseUrl, "model", diff)

            assertTrue(result.isSuccess)
            assertEquals(1, completionCalls.get())
        }
    }

    @Test
    fun generateCommitMessage_failsAfterAllRetriesExhausted() {
        val completionCalls = AtomicInteger(0)
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":4096}]}""",
            completionHandler = { _, _ ->
                completionCalls.incrementAndGet()
                response(400, """{"error":"request exceeds context window"}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/f.txt b/f.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isFailure)
            assertEquals(3, completionCalls.get())
            assertContains(result.exceptionOrNull()!!.message!!, "after 3 attempt(s)")
        }
    }

    @Test
    fun generateCommitMessage_failsFastOnNonOverflowApiError() {
        val completionCalls = AtomicInteger(0)
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { _, _ ->
                completionCalls.incrementAndGet()
                response(401, """{"error":"unauthorized"}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/f.txt b/f.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isFailure)
            assertEquals(1, completionCalls.get())
            assertContains(result.exceptionOrNull()!!.message!!, "401")
        }
    }

    @Test
    fun generateCommitMessage_usesMaxCompletionTokensForOSeriesModel() {
        var requestBody = ""
        val server = testServer(
            modelsResponse = """{"data":[{"id":"o3","context_length":32768}]}""",
            completionHandler = { body, _ ->
                requestBody = body
                response(200, """{"choices":[{"message":{"content":"Fix bug\n\n- Resolve null pointer"}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "o3",
                diff = "diff --git a/f.txt b/f.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            val payload = Json.parseToJsonElement(requestBody).jsonObject
            assertTrue("max_completion_tokens" in payload)
            assertFalse("max_tokens" in payload)
        }
    }

    @Test
    fun generateCommitMessage_returnsDefaultSummaryForBlankContent() {
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { _, _ ->
                response(200, """{"choices":[{"message":{"content":"   "}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/f.txt b/f.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            assertEquals("Update project files", result.getOrThrow().summary)
            assertEquals("", result.getOrThrow().description)
        }
    }

    @Test
    fun generateCommitMessage_stripsMarkdownFencedJsonFromResponse() {
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { _, _ ->
                response(200, """{"choices":[{"message":{"content":"```json\n{\"summary\":\"Add feature\",\"description\":\"- New endpoint\"}\n```"}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/f.txt b/f.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            assertEquals("Add feature", result.getOrThrow().summary)
            assertEquals("- New endpoint", result.getOrThrow().description)
        }
    }

    @Test
    fun generateCommitMessage_splitsOnEmDashSeparator() {
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { _, _ ->
                response(200, """{"choices":[{"message":{"content":"Fix login — resolve session timeout"}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/f.txt b/f.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            assertEquals("Fix login", result.getOrThrow().summary)
            assertEquals("- resolve session timeout", result.getOrThrow().description)
        }
    }

    @Test
    fun generateCommitMessage_splitsOnColonSeparator() {
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { _, _ ->
                response(200, """{"choices":[{"message":{"content":"Fix login: resolve session timeout"}}]}""")
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/f.txt b/f.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            assertEquals("Fix login", result.getOrThrow().summary)
            assertEquals("- resolve session timeout", result.getOrThrow().description)
        }
    }

    @Test
    fun generateCommitMessage_treatsStatus413AsContextOverflow() {
        val completionCalls = AtomicInteger(0)
        val server = testServer(
            modelsResponse = """{"data":[{"id":"model","context_length":32768}]}""",
            completionHandler = { _, _ ->
                when (completionCalls.incrementAndGet()) {
                    1 -> response(413, """{"error":"request too large"}""")
                    else -> response(200, """{"choices":[{"message":{"content":"Retry after 413\n\n- Handle payload too large"}}]}""")
                }
            },
        )

        server.use {
            val service = LlmService(client)
            val result = service.generateCommitMessage(
                address = server.baseUrl,
                modelName = "model",
                diff = "diff --git a/f.txt b/f.txt\n@@ -1 +1 @@\n-old\n+new",
            )

            assertTrue(result.isSuccess)
            assertEquals(2, completionCalls.get())
        }
    }

    @Test
    fun generateCommitMessage_reusesContextWindowCacheOnSubsequentCall() {
        val modelsCalls = AtomicInteger(0)
        val httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/v1/models") { exchange ->
            modelsCalls.incrementAndGet()
            val body = """{"data":[{"id":"model","context_length":32768}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        httpServer.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.bufferedReader().use { it.readText() }
            val body = """{"choices":[{"message":{"content":"Fix bug\n\n- Resolve issue"}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        httpServer.start()

        try {
            val baseUrl = "http://localhost:${httpServer.address.port}/v1"
            val service = LlmService(client)
            val diff = "diff --git a/f.txt b/f.txt\n@@ -1 +1 @@\n-old\n+new"

            assertTrue(service.generateCommitMessage(baseUrl, "model", diff).isSuccess)
            assertTrue(service.generateCommitMessage(baseUrl, "model", diff).isSuccess)

            assertEquals(1, modelsCalls.get())
        } finally {
            httpServer.stop(0)
        }
    }

    private fun testServer(
        modelsResponse: String,
        modelDetailResponse: Response = response(200, """{"id":"model","context_length":32768}"""),
        additionalContexts: Map<String, Response> = emptyMap(),
        completionHandler: (body: String, exchange: HttpExchange) -> Response,
    ): TestServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/models") { exchange ->
            exchange.respond(response(200, modelsResponse))
        }
        server.createContext("/v1/models/model") { exchange ->
            exchange.respond(modelDetailResponse)
        }
        additionalContexts.forEach { (path, response) ->
            server.createContext(path) { exchange ->
                exchange.respond(response)
            }
        }
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            exchange.respond(completionHandler(body, exchange))
        }
        server.start()
        return TestServer(server)
    }

    private fun HttpExchange.respond(response: Response) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(response.status, response.body.toByteArray().size.toLong())
        responseBody.use { it.write(response.body.toByteArray()) }
        close()
    }

    private fun response(status: Int, body: String) = Response(status, body)

    private data class Response(val status: Int, val body: String)

    private class TestServer(
        private val server: HttpServer,
    ) : AutoCloseable {
        val baseUrl: String = "http://localhost:${server.address.port}/v1"

        override fun close() {
            server.stop(0)
        }
    }
}
