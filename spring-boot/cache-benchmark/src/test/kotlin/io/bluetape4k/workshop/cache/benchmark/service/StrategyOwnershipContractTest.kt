package io.bluetape4k.workshop.cache.benchmark.service

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.workshop.cache.benchmark.domain.ProductRepository
import org.junit.jupiter.api.Test

class StrategyOwnershipContractTest {

    @Test
    fun `canonical strategy services do not own repository access directly`() {
        listOf(
            ReadThroughService::class.java,
            WriteThroughService::class.java,
            WriteBehindService::class.java,
        ).forEach { serviceType ->
            val ownsRepository =
                serviceType.declaredConstructors.any { constructor ->
                    constructor.parameterTypes.any { parameterType -> parameterType == ProductRepository::class.java }
                }

            ownsRepository.shouldBeFalse()
        }
    }

    @Test
    fun `write behind profile does not use per entity Spring async flusher`() {
        val flusherPresent =
            runCatching {
                Class.forName("io.bluetape4k.workshop.cache.benchmark.service.WriteBehindFlusher")
            }.isSuccess

        flusherPresent.shouldBeFalse()
    }
}
