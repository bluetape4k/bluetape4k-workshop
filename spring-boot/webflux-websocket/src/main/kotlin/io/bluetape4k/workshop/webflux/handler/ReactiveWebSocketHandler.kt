package io.bluetape4k.workshop.webflux.handler

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.webflux.service.QuoteGenerator
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.time.Duration

@Component
class ReactiveWebSocketHandler(
    private val quoteGenerator: QuoteGenerator,
): WebSocketHandler {

    companion object : KLoggingChannel()

    /**
     * 새 WebSocket connection 이 수립될 때 호출되며 session 처리를 허용합니다.
     *
     *
     * session 처리 방법의 자세한 설명과 예제는 class-level doc 과 reference manual 을 참고합니다.
     *
     * @param session 처리할 session 입니다.
     * @return application 의 session 처리가 완료되는 시점을 나타냅니다.
     * inbound message stream 완료(connection closing)와 outbound message stream 및 message write 완료를 반영해야 합니다.
     */
    override fun handle(session: WebSocketSession): Mono<Void> {
        val flux = quoteGenerator
            .fetchQuoteStringAsFlux(Duration.ofSeconds(2))
            .map { quoteStr -> session.textMessage(quoteStr) }

        return session.send(flux)
            .and(session.receive().map { it.payloadAsText }.log())
    }
}
