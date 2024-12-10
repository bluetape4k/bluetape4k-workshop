package io.bluetape4k.workshop.webflux.virtualthread.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.workshop.webflux.virtualthread.AbstractWebfluxVirtualThreadTest
import org.junit.jupiter.api.Test

class HttpbinControllerTest: AbstractWebfluxVirtualThreadTest() {

    companion object: KLogging()

    @Test
    fun `call httpbin delay via mono`() {
        client.httpGet("/httpbin/delay/mono/1")
    }

    @Test
    fun `call httpbin delay via coroutines`() {
        client.httpGet("/httpbin/delay/suspend/1")
    }
}
