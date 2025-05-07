package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.webflux.virtualthread.AbstractWebfluxVirtualThreadTest
import kotlinx.coroutines.reactive.awaitSingle
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.returnResult

class HttpbinControllerTest: AbstractWebfluxVirtualThreadTest() {

    companion object: KLogging()

    @Test
    fun `call httpbin delay via mono`() = runSuspendIO {
        client.httpGet("/httpbin/delay/mono/1")
            .returnResult<String>().responseBody
            .awaitSingle()
    }

    @Test
    fun `call httpbin delay via coroutines`() = runSuspendIO {
        client.httpGet("/httpbin/delay/suspend/1")
            .returnResult<String>().responseBody
            .awaitSingle()
    }
}
