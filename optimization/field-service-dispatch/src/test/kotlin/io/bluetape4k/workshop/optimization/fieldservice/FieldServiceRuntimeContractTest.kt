package io.bluetape4k.workshop.optimization.fieldservice

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import org.junit.jupiter.api.Test

class FieldServiceRuntimeContractTest {

    @Test
    fun `field service module runs on Java 25 virtual threads`() {
        Runtime.version().feature() shouldBeEqualTo 25
        VirtualThreads.runtimeName() shouldBeEqualTo "jdk25"
        VirtualThreads.executorService().use { executor ->
            executor.submit<Boolean> { Thread.currentThread().isVirtual }.get().shouldBeTrue()
        }
    }
}
