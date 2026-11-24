package com.jobcelis

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Checks the paths and bodies the client sends. */
class RequestShapeTest {

    private val paths = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private val http = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            bodies += request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() } ?: ""
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    private val client = JobcelisClient("test_key", "https://api.test", http)

    private fun lastBody(): JsonObject = JsonParser.parseString(bodies.last()).asJsonObject

    @Test
    fun `restrictProcessing sends the password`() = runBlocking<Unit> {
        client.restrictProcessing("secret")
        assertEquals("/api/v1/me/restrict", paths.last())
        assertEquals("secret", lastBody().get("password").asString)
    }

    @Test
    fun `status calls health`() = runBlocking<Unit> {
        client.status()
        assertEquals("/health", paths.last())
    }

    @Test
    fun `addMember defaults to viewer`() = runBlocking<Unit> {
        client.addMember("p1", "a@example.com")
        assertEquals("viewer", lastBody().get("role").asString)
    }

    @Test
    fun `testPipeline and updateRetentionPolicy wrap their bodies`() = runBlocking<Unit> {
        client.testPipeline("pl1", mapOf("id" to "1"))
        assertEquals("1", lastBody().getAsJsonObject("payload").get("id").asString)
        client.updateRetentionPolicy(mapOf("events_days" to 30))
        assertEquals(30, lastBody().getAsJsonObject("retention_policy").get("events_days").asInt)
    }

    @Test
    fun `createPipeline sends webhook_id`() = runBlocking<Unit> {
        client.createPipeline("p", "wh_1", listOf("order.created"))
        assertEquals("wh_1", lastBody().get("webhook_id").asString)
    }

    @Test
    fun `path parameters are encoded`() = runBlocking<Unit> {
        client.getEvent("a/b c")
        assertEquals("/api/v1/events/a%2Fb%20c", paths.last())
    }

    @Test
    fun `close leaves a caller-provided OkHttpClient usable`() {
        client.close()
        assertFalse(http.dispatcher.executorService.isShutdown)
    }
}
