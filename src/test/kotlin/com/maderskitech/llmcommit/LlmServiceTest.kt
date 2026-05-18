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
import kotlin.test.assertTrue

class LlmServiceTest {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

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

    private fun testServer(
        modelsResponse: String,
        completionHandler: (body: String, exchange: HttpExchange) -> Response,
    ): TestServer {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/models") { exchange ->
            exchange.respond(response(200, modelsResponse))
        }
        server.createContext("/v1/models/model") { exchange ->
            exchange.respond(response(200, """{"id":"model","context_length":32768}"""))
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
