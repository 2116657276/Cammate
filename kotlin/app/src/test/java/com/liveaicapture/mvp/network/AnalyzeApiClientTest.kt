package com.liveaicapture.mvp.network

import com.liveaicapture.mvp.data.AnalyzeEvent
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzeApiClientTest {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@AnalyzeApiClientTest.executor
        start()
    }

    @After
    fun closeServer() {
        server.stop(0)
        executor.shutdownNow()
    }

    @Test
    fun providerFailureIsAnErrorInsteadOfASuggestion() = runBlocking {
        serve("""{"type":"error","text":"云端限流，请稍后重试"}
{"type":"done"}
""")
        val events = mutableListOf<AnalyzeEvent>()
        analyze(events::add)
        assertEquals(listOf(AnalyzeEvent.Error("云端限流，请稍后重试"), AnalyzeEvent.Done), events)
    }

    @Test
    fun normalWarningRemainsAValidSuggestion() = runBlocking {
        serve("""{"type":"ui","text":"请避开逆光","level":"warn"}
{"type":"done"}
""")
        val events = mutableListOf<AnalyzeEvent>()
        analyze(events::add)
        assertEquals(listOf(AnalyzeEvent.Ui("请避开逆光", "warn"), AnalyzeEvent.Done), events)
    }

    @Test
    fun cancelledRequestCannotDeliverALateSuggestion() = runBlocking {
        val firstEvent = CountDownLatch(1)
        val finishResponse = CountDownLatch(1)
        server.createContext("/analyze") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write("{\"type\":\"scene\",\"scene\":\"portrait\"}\n".toByteArray())
                body.flush()
                if (finishResponse.await(3, TimeUnit.SECONDS)) {
                    runCatching { body.write("{\"type\":\"ui\",\"text\":\"旧账号建议\",\"level\":\"info\"}\n".toByteArray()) }
                }
            }
            exchange.close()
        }
        val events = Collections.synchronizedList(mutableListOf<AnalyzeEvent>())
        val request = launch(Dispatchers.Default) {
            analyze { event ->
                events.add(event)
                firstEvent.countDown()
            }
        }
        try {
            assertTrue(firstEvent.await(3, TimeUnit.SECONDS))
            request.cancel()
        } finally {
            finishResponse.countDown()
        }
        withTimeout(4000) { request.join() }
        assertTrue(request.isCancelled)
        assertFalse(events.any { it is AnalyzeEvent.Ui })
    }

    private fun serve(response: String) {
        server.createContext("/analyze") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(response.toByteArray(Charsets.UTF_8)) }
            exchange.close()
        }
    }

    private suspend fun analyze(onEvent: (AnalyzeEvent) -> Unit) {
        AnalyzeApiClient().analyze(
            serverUrl = "http://127.0.0.1:${server.address.port}",
            bearerToken = "review-session",
            imageBase64 = "aXNvbGF0ZWQtdGVzdA==",
            rotationDegrees = 0,
            lensFacing = "back",
            exposureCompensation = 0,
            captureMode = "auto",
            sceneHint = "portrait",
            previousTipText = "",
            frameStable = true,
            stabilityScore = 1f,
            onRawLine = {},
            onEvent = onEvent,
        )
    }
}
