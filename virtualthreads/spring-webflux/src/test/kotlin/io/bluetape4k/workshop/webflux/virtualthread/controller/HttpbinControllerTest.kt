package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.webflux.virtualthread.AbstractWebfluxVirtualThreadTest
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class HttpbinControllerTest: AbstractWebfluxVirtualThreadTest() {

    companion object: KLoggingChannel()

    @Test
    fun `call httpbin delay via mono`() = runSuspendIO {
        client
            .get()
            .uri("/httpbin/delay/mono/1")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<String>().responseBody
            .awaitSingle()
    }

    @Test
    fun `call httpbin delay via coroutines`() = runSuspendIO {
        client
            .get()
            .uri("/httpbin/delay/suspend/1")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<String>().responseBody
            .awaitSingle()
    }
}
