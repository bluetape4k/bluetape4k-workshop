package io.bluetape4k.workshop.shared.redis

import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.testcontainers.storage.TestcontainersRedisRuntime
import org.junit.jupiter.api.Test

/** bluetape4k-testcontainers의 Redis adapter를 Workshop에서 직접 사용하는지 검증합니다. */
class TestcontainersRedisRuntimeUpstreamContractTest {

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
