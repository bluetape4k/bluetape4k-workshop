package io.bluetape4k.workshop.cache.redis.prefetcher

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.cache.redis.domain.CountryRepository
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@Profile("app")
class CountryPrefetcher(private val repository: CountryRepository) {

    companion object: KLoggingChannel()

    @Scheduled(fixedDelay = 10)
    fun retreiveCountry() {
        val code = CountryRepository.SAMPLE_COUNTRY_CODES.random()
        log.info { "Looking for country with code [$code]" }
        repository.findByCode(code)
    }
}
