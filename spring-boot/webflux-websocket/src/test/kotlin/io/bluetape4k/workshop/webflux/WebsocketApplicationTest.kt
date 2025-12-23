package io.bluetape4k.workshop.webflux

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.webflux.model.Event
import io.bluetape4k.workshop.webflux.model.Quote
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.reactive.asFlow
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import java.math.BigDecimal

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebsocketApplicationTest(
    @param:Autowired val context: ApplicationContext,
) {
    companion object: KLoggingChannel()

    private val client by lazy {
        WebTestClient.bindToApplicationContext(context).build()
    }

    @Test
    fun `context loading`() {
        // check context loading 
    }

    @Test
    fun `get quotes`() = runSuspendIO {
        client
            .get()
            .uri("/quotes")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.APPLICATION_NDJSON)
            .returnResult<Event>().responseBody
            .asFlow()
            .onEach { event ->
                log.debug { "received event=$event" }
                event.data.all { it.price > BigDecimal.ZERO }.shouldBeTrue()
            }
            .collect()
    }

    @Test
    fun `fetch quotes by flow`() = runSuspendIO {
        client
            .get()
            .uri("/quotes/100")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.APPLICATION_NDJSON)
            .returnResult<Quote>().responseBody
            .asFlow()
            .onEach { quote ->
                log.debug { "received quote=$quote" }
                quote.price shouldBeGreaterThan BigDecimal.ZERO
            }
            .collect()
    }
}
