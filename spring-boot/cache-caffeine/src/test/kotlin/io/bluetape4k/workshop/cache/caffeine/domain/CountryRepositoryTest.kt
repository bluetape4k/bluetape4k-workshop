package io.bluetape4k.workshop.cache.caffeine.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeGreaterThan
import io.bluetape4k.assertions.shouldBeLessOrEqualTo
import io.bluetape4k.assertions.shouldBeLessThan
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.junit5.concurrency.MultithreadingTester
import io.bluetape4k.junit5.concurrency.StructuredTaskScopeTester
import io.bluetape4k.junit5.coroutines.SuspendedJobTester
import io.bluetape4k.junit5.coroutines.runSuspendDefault
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.utils.Runtimex
import io.bluetape4k.workshop.cache.caffeine.AbstractCaffeineCacheApplicationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnJre
import org.junit.jupiter.api.condition.JRE
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.context.annotation.Profile
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

@Profile("app")
class CountryRepositoryTest(
    @param:Autowired private val countryRepo: CountryRepository,
    @param:Autowired private val cacheManager: CacheManager,
): AbstractCaffeineCacheApplicationTest() {

    companion object: KLoggingChannel() {
        internal const val KR = "KR"
        internal const val US = "US"
        internal const val EXPECTED_MILLIS = 400L
    }

    @BeforeEach
    fun beforeEach() {
        countryRepo.evictCache(KR)
    }

    @Test
    fun `get country at first`() {
        countryRepo.evictCache(KR)

        val kr = measureTimeMillis {
            countryRepo.findByCode(KR)
        }
        kr shouldBeGreaterThan EXPECTED_MILLIS

        val kr2 = measureTimeMillis {
            countryRepo.findByCode(KR)
        }
        kr2 shouldBeLessThan EXPECTED_MILLIS

        log.debug { "kr=$kr msec, kr2=$kr2 msec" }
    }

    @Test
    fun `evict cached country`() {
        countryRepo.evictCache(US)
        cachedCountry(US).shouldBeNull()

        val us = measureTimeMillis {
            countryRepo.findByCode(US)
        }
        us shouldBeGreaterThan EXPECTED_MILLIS
        cachedCountry(US).shouldNotBeNull()

        val usCached = measureTimeMillis {
            countryRepo.findByCode(US)
        }
        usCached shouldBeLessThan EXPECTED_MILLIS

        countryRepo.evictCache(US)
        cachedCountry(US).shouldBeNull()

        val usEvicted = measureTimeMillis {
            countryRepo.findByCode(US)
        }
        usEvicted shouldBeGreaterThan EXPECTED_MILLIS
        cachedCountry(US).shouldNotBeNull()
    }

    @Test
    fun `reject blank country code`() {
        assertFailsWith<IllegalArgumentException> {
            countryRepo.findByCode(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            countryRepo.evictCache(" ")
        }
    }

    @Test
    fun `get random countries in multi-threading`() {
        val codeMap = ConcurrentHashMap<String, Country>()

        measureTimeMillis {
            MultithreadingTester()
                .workers(8 * Runtimex.availableProcessors)
                .rounds(8)
                .add {
                    val country = retreiveCountry()
                    codeMap[country.code] = country
                }
                .run()
        } shouldBeLessThan 8 * Runtimex.availableProcessors * 8 * EXPECTED_MILLIS

        codeMap.size shouldBeLessOrEqualTo CountryRepository.SAMPLE_COUNTRY_CODES.size
    }

    @EnabledOnJre(JRE.JAVA_21)
    @Test
    fun `get random countries in virtual threads`() {
        val codeMap = ConcurrentHashMap<String, Country>()

        measureTimeMillis {
            StructuredTaskScopeTester()
                .rounds(8 * 8 * Runtimex.availableProcessors)
                .add {
                    val country = retreiveCountry()
                    codeMap[country.code] = country
                }
                .run()
        } shouldBeLessThan 8 * Runtimex.availableProcessors * 8 * EXPECTED_MILLIS

        codeMap.size shouldBeLessOrEqualTo CountryRepository.SAMPLE_COUNTRY_CODES.size
    }

    @Test
    fun `get random countries in 코루틴`() = runSuspendDefault {
        val codeMap = ConcurrentHashMap<String, Country>()

        measureTimeMillis {
            SuspendedJobTester()
                .workers(8 * Runtimex.availableProcessors)
                .rounds(8)
                .add {
                    val country = retreiveCountry()
                    codeMap[country.code] = country
                }
                .run()
        } shouldBeLessThan 8 * Runtimex.availableProcessors * 8 * EXPECTED_MILLIS

        codeMap.size shouldBeLessOrEqualTo CountryRepository.SAMPLE_COUNTRY_CODES.size
    }

    private fun retreiveCountry(): Country {
        val code = CountryRepository.SAMPLE_COUNTRY_CODES.random()
        log.info { "Looking for country with code [$code]" }
        return countryRepo.findByCode(code)
    }

    private fun cachedCountry(code: String): Country? =
        cacheManager.getCache("cache:contries")?.get("country:$code", Country::class.java)
}
