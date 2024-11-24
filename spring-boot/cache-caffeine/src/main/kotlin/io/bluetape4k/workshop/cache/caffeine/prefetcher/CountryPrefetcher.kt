package io.bluetape4k.workshop.cache.caffeine.prefetcher

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.cache.caffeine.domain.CountryRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CountryPrefetcher(private val repository: CountryRepository) {

    companion object: KLogging()

    @Scheduled(fixedDelay = 10)
    fun retreiveCountry() {
        val code = CountryRepository.SAMPLE_COUNTRY_CODES.random()
        log.info { "Looking for country with code [$code]" }
        repository.findByCode(code)
    }
}
