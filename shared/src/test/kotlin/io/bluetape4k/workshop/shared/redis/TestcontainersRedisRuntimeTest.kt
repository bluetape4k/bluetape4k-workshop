package io.bluetape4k.workshop.shared.redis

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeBlank
import org.junit.jupiter.api.Test

class TestcontainersRedisRuntimeTest {

    @Test
    fun `testcontainers runtime exposes a live endpoint and closes its owned server`() {
        val runtime = TestcontainersRedisRuntime()

        try {
            val endpoint = runtime.start()

            endpoint.host.shouldNotBeBlank()
            (endpoint.port in 1..65535).shouldBeTrue()
            endpoint.url.shouldNotBeBlank()
            runtime.isRunning.shouldBeTrue()
        } finally {
            runtime.close()
        }

        runtime.isClosed.shouldBeTrue()
    }
}
