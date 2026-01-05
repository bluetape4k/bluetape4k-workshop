package io.bluetape4k.workshop.cache.redis.domain

import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.cache.redis.AbstractRedisCacheTest
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldBeLessOrEqualTo
import org.amshove.kluent.shouldBeLessThan
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnJre
import org.junit.jupiter.api.condition.JRE
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Profile
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

@Profile("app")
class CountryRepositoryTest(
    @param:Autowired private val countryRepo: CountryRepository,
): AbstractRedisCacheTest() {

    companion object: KLoggingChannel() {
        internal const val KR = "KR"
        internal const val US = "US"
        internal const val EXPECTED_MILLIS = 400L
    }

    @BeforeEach
    fun beforeEach() {
        countryRepo.evictCache(US)
        countryRepo.evictCache(KR)
    }

    @Test
    fun `get country at first`() {
        countryRepo.evictCache(KR)

        val kr = measureTimeMillis {
            countryRepo.findByCode(KR)
        }

        Thread.sleep(10)

        val kr2 = measureTimeMillis {
            countryRepo.findByCode(KR)
        }

        log.debug { "kr=$kr msec, kr2=$kr2 msec" }

        kr shouldBeGreaterThan EXPECTED_MILLIS
        kr2 shouldBeLessThan EXPECTED_MILLIS
    }

    @Test
    fun `evict cached country`() {
        countryRepo.evictCache(US)

        val us = measureTimeMillis {
            countryRepo.findByCode(US)
        }

        Thread.sleep(10)

        val usCached = measureTimeMillis {
            countryRepo.findByCode(US)
        }

        countryRepo.evictCache(US)

        Thread.sleep(10)

        val usEvicted = measureTimeMillis {
            countryRepo.findByCode(US)
        }

        us shouldBeGreaterThan EXPECTED_MILLIS
        usCached shouldBeLessThan EXPECTED_MILLIS
        usEvicted shouldBeGreaterThan EXPECTED_MILLIS
    }

    @Test
    fun `get random countries in multi threading`() {
        val codeMap = ConcurrentHashMap<String, Country>()

        measureTimeMillis {
            MultithreadingTester()
                .numThreads(8)
                .roundsPerThread(8)
                .add {
                    val country = retreiveCountry()
                    codeMap[country.code] = country
                }
                .run()
        } shouldBeLessThan 8 * 8 * EXPECTED_MILLIS

        codeMap.size shouldBeLessOrEqualTo CountryRepository.SAMPLE_COUNTRY_CODES.size
    }

    @EnabledOnJre(JRE.JAVA_21)
    @Test
    fun `get random countries in virtual threads`() {
        val codeMap = ConcurrentHashMap<String, Country>()

        measureTimeMillis {
            StructuredTaskScopeTester()
                .roundsPerTask(8 * 8)
                .add {
                    val country = retreiveCountry()
                    codeMap[country.code] = country
                }
                .run()
        } shouldBeLessThan 8 * 8 * EXPECTED_MILLIS

        codeMap.size shouldBeLessOrEqualTo CountryRepository.SAMPLE_COUNTRY_CODES.size
    }

    private fun retreiveCountry(): Country {
        val code = CountryRepository.SAMPLE_COUNTRY_CODES.random()
        log.info { "Looking for country with code [$code]" }
        return countryRepo.findByCode(code)
    }
}
