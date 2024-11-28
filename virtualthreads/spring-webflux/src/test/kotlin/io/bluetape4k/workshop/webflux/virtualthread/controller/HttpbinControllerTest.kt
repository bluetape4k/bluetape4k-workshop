package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.webflux.virtualthread.AbstractWebfluxVirtualThreadTest
import org.junit.jupiter.api.Test

class HttpbinControllerTest: AbstractWebfluxVirtualThreadTest() {

    companion object: KLogging()

    @Test
    fun `call httpbin delay via mono`() {
        getApi("/httpbin/delay/mono/1")
            .expectStatus().isOk
    }

    @Test
    fun `call httpbin delay via coroutines`() {
        getApi("/httpbin/delay/suspend/1")
            .expectStatus().isOk
    }
}
