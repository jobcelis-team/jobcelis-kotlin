package com.jobcelis

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RetryTest {

    /** A scripted step: either an HTTP response or a network error. */
    private sealed interface Step
    private data class Reply(val code: Int, val body: String = "{}", val headers: Map<String, String> = emptyMap()) : Step
    private object NetworkError : Step

    /** Fake transport: answers requests from [steps] in order and records what was sent. */
    private class FakeServer(vararg steps: Step) : Interceptor {
        private val queue = ArrayDeque(steps.toList())
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<String>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            bodies += request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() } ?: ""
            return when (val step = queue.removeFirstOrNull() ?: Reply(200)) {
                is NetworkError -> throw IOException("connection reset")
                is Reply -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(step.code)
                    .message("status ${step.code}")
                    .apply { step.headers.forEach { (k, v) -> header(k, v) } }
                    .body(step.body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }
    }

    private fun client(server: FakeServer, maxRetries: Int? = null): JobcelisClient {
        val http = OkHttpClient.Builder().addInterceptor(server).build()
        val client = if (maxRetries == null) {
            JobcelisClient("test_key", "https://api.test", http)
        } else {
            JobcelisClient("test_key", "https://api.test", http, maxRetries = maxRetries)
        }
        client.retryBaseDelayMillis = 1L
        return client
    }

    @Test
    fun `GET is retried on 503 until it succeeds`() = runBlocking<Unit> {
        val server = FakeServer(Reply(503), Reply(503), Reply(200, """{"id":"evt_1"}"""))
        val result = client(server).getEvent("evt_1")
        assertEquals("evt_1", result.get("id").asString)
        assertEquals(3, server.requests.size)
    }

    @Test
    fun `429 with Retry-After is retried`() = runBlocking<Unit> {
        val server = FakeServer(Reply(429, headers = mapOf("Retry-After" to "0")), Reply(200, """{"ok":true}"""))
        val result = client(server).listEvents()
        assertTrue(result.get("ok").asBoolean)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `sendEvent sends the same idempotency key on every attempt`() = runBlocking<Unit> {
        val server = FakeServer(NetworkError, Reply(503), Reply(202, """{"id":"evt_1"}"""))
        client(server).sendEvent("order.created", mapOf("order_id" to "123"))
        assertEquals(3, server.requests.size)
        val keys = server.requests.map { it.header("X-Idempotency-Key") }
        assertNotNull(keys[0])
        assertTrue(keys.all { it == keys[0] }, "keys differ: $keys")
    }

    @Test
    fun `sendEvent keeps a caller supplied idempotency key`() = runBlocking<Unit> {
        val server = FakeServer(Reply(502), Reply(202))
        client(server).sendEvent("order.created", mapOf("order_id" to "123"), "my-key")
        assertEquals(listOf("my-key", "my-key"), server.requests.map { it.header("X-Idempotency-Key") })
    }

    @Test
    fun `sendEvents adds idempotency keys to items without one and keeps existing ones`() = runBlocking<Unit> {
        val server = FakeServer(Reply(503), Reply(202))
        client(server).sendEvents(
            listOf(
                mapOf("topic" to "a", "payload" to mapOf("n" to 1)),
                mapOf("topic" to "b", "payload" to mapOf("n" to 2), "idempotency_key" to "existing")
            )
        )
        assertEquals(2, server.requests.size)
        assertEquals(server.bodies[0], server.bodies[1], "retry must resend the same body")
        val events = JsonParser.parseString(server.bodies[0]).asJsonObject.getAsJsonArray("events")
        val generated = events[0].asJsonObject.get("idempotency_key")?.asString
        assertNotNull(generated)
        assertTrue(generated.isNotEmpty())
        assertEquals("existing", events[1].asJsonObject.get("idempotency_key").asString)
    }

    @Test
    fun `POST of a non-event resource is not retried on a network error`() {
        val server = FakeServer(NetworkError, Reply(201))
        assertFailsWith<IOException> {
            runBlocking { client(server).createWebhook("https://example.com/hook") }
        }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `POST of a non-event resource is retried on 503`() = runBlocking<Unit> {
        val server = FakeServer(Reply(503), Reply(201, """{"id":"wh_1"}"""))
        val result = client(server).createWebhook("https://example.com/hook")
        assertEquals("wh_1", result.get("id").asString)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `GET is retried on a network error`() = runBlocking<Unit> {
        val server = FakeServer(NetworkError, Reply(200, """{"ok":true}"""))
        client(server).health()
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `maxRetries 0 makes exactly one request`() {
        val server = FakeServer(Reply(503), Reply(200))
        val e = assertFailsWith<JobcelisException> {
            runBlocking { client(server, maxRetries = 0).getEvent("evt_1") }
        }
        assertEquals(503, e.statusCode)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `gives up after maxRetries and throws the usual exception`() {
        val server = FakeServer(Reply(503), Reply(503), Reply(503), Reply(200))
        val e = assertFailsWith<JobcelisException> {
            runBlocking { client(server, maxRetries = 2).getEvent("evt_1") }
        }
        assertEquals(503, e.statusCode)
        assertEquals(3, server.requests.size)
    }

    @Test
    fun `400 is not retried`() {
        val server = FakeServer(Reply(400, """{"error":"bad"}"""), Reply(200))
        val e = assertFailsWith<JobcelisException> {
            runBlocking { client(server).getEvent("evt_1") }
        }
        assertEquals(400, e.statusCode)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `500 is not retried`() {
        val server = FakeServer(Reply(500), Reply(200))
        val e = assertFailsWith<JobcelisException> {
            runBlocking { client(server).sendEvent("order.created", mapOf("order_id" to "123")) }
        }
        assertEquals(500, e.statusCode)
        assertEquals(1, server.requests.size)
    }
}
