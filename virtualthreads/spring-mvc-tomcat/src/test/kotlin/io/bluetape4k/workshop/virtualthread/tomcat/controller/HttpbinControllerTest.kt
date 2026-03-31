package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class HttpbinControllerTest: AbstractVirtualThreadMvcTest() {

    companion object: KLoggingChannel()

    @Test
    fun `call httpbin delay via mono`() = runSuspendIO {
        webTestClient
            .get()
            .uri("/httpbin/block/1")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<String>().responseBody
            .awaitSingle()
    }
}
