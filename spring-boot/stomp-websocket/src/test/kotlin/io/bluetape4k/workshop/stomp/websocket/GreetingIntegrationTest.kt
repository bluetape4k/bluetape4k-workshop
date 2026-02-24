package io.bluetape4k.workshop.stomp.websocket

import io.bluetape4k.junit5.awaitility.untilSuspending
import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.stomp.websocket.model.Greeting
import io.bluetape4k.workshop.stomp.websocket.model.HelloMessage
import kotlinx.coroutines.future.await
import org.amshove.kluent.shouldBeEqualTo
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.JacksonJsonMessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandler
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.springframework.web.socket.sockjs.client.SockJsClient
import org.springframework.web.socket.sockjs.client.Transport
import org.springframework.web.socket.sockjs.client.WebSocketTransport
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jsonMapper
import java.lang.reflect.Type
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GreetingIntegrationTest(
    @param:LocalServerPort private val port: Int,
    @Autowired private val jacksonJsonMapper: JsonMapper,
) {
    companion object: KLogging()

    val wsUrl by lazy { "ws://localhost:$port/gs-guide-websocket" }

    private lateinit var stompClient: WebSocketStompClient
    private val headers = WebSocketHttpHeaders()

    @BeforeEach
    fun beforeEach() {
        val transports = listOf<Transport>(WebSocketTransport(StandardWebSocketClient()))
        val socketJsClient = SockJsClient(transports)
        this.stompClient = WebSocketStompClient(socketJsClient).apply {
            messageConverter = JacksonJsonMessageConverter(jsonMapper())
        }
        log.debug { "Local server port: $port" }
    }

    @Test
    fun `get greeting`() {
        val received = AtomicReference<Greeting>(null)
        val failure = AtomicReference<Throwable>(null)
        val handler: StompSessionHandler = getStopmSessionHandler(received, failure)

        val session = stompClient.connectAsync(wsUrl, headers, handler, port).get()

        log.debug { "Send HelloMessage to /app/hello" }
        try {
            session.send("/app/hello", HelloMessage("Spring"))
        } catch (e: Throwable) {
            failure.set(e)
        }

        await until { received.get() != null || failure.get() != null }

        if (failure.get() == null) {
            received.get()!!.content shouldBeEqualTo "Hello, Spring!"
        } else {
            fail(failure.get())
        }
    }

    @Test
    fun `get greeting with coroutines`() = runSuspendIO {
        val received = AtomicReference<Greeting>(null)
        val failure = AtomicReference<Throwable>(null)
        val handler: StompSessionHandler = getStopmSessionHandler(received, failure)

        val session = stompClient.connectAsync(wsUrl, headers, handler, port).await()

        log.debug { "Send HelloMessage to /app/hello" }
        try {
            session.send("/app/hello", HelloMessage("Spring"))
        } catch (e: Throwable) {
            failure.set(e)
        }

        await untilSuspending { received.get() != null || failure.get() != null }

        if (failure.get() == null) {
            received.get()!!.content shouldBeEqualTo "Hello, Spring!"
        } else {
            fail(failure.get())
        }
    }

    private fun getStopmSessionHandler(
        received: AtomicReference<Greeting>,
        failure: AtomicReference<Throwable>,
    ): StompSessionHandler = object: TestSessionHandler(failure) {
        override fun afterConnected(session: StompSession, connectedHeaders: StompHeaders) {
            log.debug { "Stomp session connected. subscribe /topic/greetings" }
            session.subscribe(
                "/topic/greetings",
                object: StompFrameHandler {
                    override fun getPayloadType(headers: StompHeaders): Type = Greeting::class.java

                    override fun handleFrame(headers: StompHeaders, payload: Any?) {
                        log.debug { "Payload: $payload" }
                        try {
                            val greeting = payload as Greeting
                            received.set(greeting)
                            log.debug { "Receive: $greeting" }
                        } catch (e: Throwable) {
                            failure.set(e)
                        } finally {
                            session.disconnect()
                        }
                    }
                }
            )
        }
    }
}
