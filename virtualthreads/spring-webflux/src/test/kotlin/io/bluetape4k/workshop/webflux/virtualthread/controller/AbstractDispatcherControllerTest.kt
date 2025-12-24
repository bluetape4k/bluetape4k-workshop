package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.webflux.virtualthread.AbstractWebfluxVirtualThreadTest
import io.bluetape4k.workshop.webflux.virtualthread.model.Banner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeAfter
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.body
import org.springframework.test.web.reactive.server.returnResult
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant

abstract class AbstractDispatcherControllerTest: AbstractWebfluxVirtualThreadTest() {

    companion object: KLogging() {
        const val REPEAT_SIZE = 3
    }

    abstract val path: String

    @Test
    fun `get index`() = runSuspendIO {
        val now = Instant.now()

        val response = client
            .get()
            .uri("/$path")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle()

        response.createdAt shouldBeAfter now
    }

    @RepeatedTest(REPEAT_SIZE)
    fun suspending() = runSuspendIO {
        val now = Instant.now()
        client
            .get()
            .uri("/$path/suspend")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle()
            .createdAt shouldBeAfter now
    }

    @RepeatedTest(REPEAT_SIZE)
    fun deferred() = runSuspendIO {
        val now = Instant.now()
        client
            .get()
            .uri("/$path/deferred")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .awaitSingle()
            .createdAt shouldBeAfter now
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `sequential flow`() = runSuspendIO {
        val now = Instant.now()
        val banners = client
            .get()
            .uri("/$path/sequential-flow?size=$REPEAT_SIZE")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .asFlow()
            .toList()

        banners.all { it.createdAt.isAfter(now) }.shouldBeTrue()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `concurrent flow`() = runSuspendIO {
        val now = Instant.now()
        val banners = client
            .get()
            .uri("/$path/concurrent-flow?size=$REPEAT_SIZE")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<Banner>().responseBody
            .asFlow()
            .toList()

        banners.all { it.createdAt.isAfter(now) }.shouldBeTrue()
    }

    @RepeatedTest(REPEAT_SIZE)
    fun error() = runSuspendIO {
        client
            .get()
            .uri("/$path/error")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @RepeatedTest(REPEAT_SIZE)
    fun `request as stream`() = runSuspendIO {
        val request = (1..5).asFlow()
            .onEach {
                delay(5)
                log.debug { "request node=$it" }
            }
            .map {
                JsonNodeFactory.instance.numberNode(it)
            }

        val nodes = client
            .post()
            .uri("/$path/request-as-flow")
            .body(request)
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<JsonNode>().responseBody
            .asFlow()
            .toList()

        nodes shouldBeEqualTo request.toList()
    }
}
