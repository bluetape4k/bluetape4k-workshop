package io.bluetape4k.workshop.cache.redis.domain

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireNotBlank
import org.springframework.cache.annotation.CacheConfig
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

/**
 * Spring Cache 와 Redis 로 backing 되는 느린 country lookup 을 simulate 합니다.
 *
 * 빈 country code 는 Redis cache loading 또는 eviction 작업을 진행하기 전에
 * bluetape4k validation helper 로 거부합니다.
 */
@Component
@CacheConfig(cacheNames = ["cache:contries"])
class CountryRepository {

    companion object: KLoggingChannel() {
        @JvmStatic
        val SAMPLE_COUNTRY_CODES: List<String> =
            listOf(
                "AF", "AX",
                "AL", "DZ", "AS", "AD", "AO", "AI", "AQ", "AG", "AR", "AM", "AW", "AU", "AT",
                "AZ", "BS", "BH", "BD", "BB", "BY", "BE", "BZ", "BJ", "BM", "BT", "BO", "BQ",
                "BA", "BW", "BV", "BR", "IO", "BN", "BG", "BF", "BI", "KH", "CM", "CA", "CV",
                "KY", "CF", "TD", "CL", "CN", "CX", "CC", "CO", "KM", "CG", "CD", "CK", "CR",
                "CI", "HR", "CU", "CW", "CY", "CZ", "DK", "DJ", "DM", "DO", "EC", "EG", "SV",
                "GQ", "ER", "EE", "ET", "FK", "FO", "FJ", "FI", "FR", "GF", "PF", "TF", "GA",
                "GM", "GE", "DE", "GH", "GI", "GR", "GL", "GD", "GP", "GU", "GT", "GG", "GN",
                "GW", "GY", "HT", "HM", "VA", "HN", "HK", "HU", "IS", "IN", "ID", "IR", "IQ",
                "IE", "IM", "IL", "IT", "JM", "JP", "JE", "JO", "KZ", "KE", "KI", "KP", "KR",
                "KW", "KG", "LA", "LV", "LB", "LS", "LR", "LY", "LI", "LT", "LU", "MO", "MK",
                "MG", "MW", "MY", "MV", "ML", "MT", "MH", "MQ", "MR", "MU", "YT", "MX", "FM",
                "MD", "MC", "MN", "ME", "MS", "MA", "MZ", "MM", "NA", "NR", "NP", "NL", "NC",
                "NZ", "NI", "NE", "NG", "NU", "NF", "MP", "NO", "OM", "PK", "PW", "PS", "PA",
                "PG", "PY", "PE", "PH", "PN", "PL", "PT", "PR", "QA", "RE", "RO", "RU", "RW",
                "BL", "SH", "KN", "LC", "MF", "PM", "VC", "WS", "SM", "ST", "SA", "SN", "RS",
                "SC", "SL", "SG", "SX", "SK", "SI", "SB", "SO", "ZA", "GS", "SS", "ES", "LK",
                "SD", "SR", "SJ", "SZ", "SE", "CH", "SY", "TW", "TJ", "TZ", "TH", "TL", "TG",
                "TK", "TO", "TT", "TN", "TR", "TM", "TC", "TV", "UG", "UA", "AE", "GB", "US",
                "UM", "UY", "UZ", "VU", "VE", "VN", "VG", "VI", "WF", "EH", "YE", "ZM", "ZW"
            )
    }

    val countrySize: Int get() = SAMPLE_COUNTRY_CODES.size

    /**
     * ISO 형식에 가까운 [code] 로 country 를 찾습니다.
     *
     * 첫 valid lookup 은 Redis cache fill 동작을 보여주기 위해 의도적으로 400 ms 대기합니다.
     * 후속 lookup 은 Redis 에서 제공합니다.
     *
     * @throws IllegalArgumentException [code] 가 비어 있을 때 발생합니다.
     */
    @Cacheable(key = "'country:' + #code")
    fun findByCode(code: String): Country {
        code.requireNotBlank("code")
        Thread.sleep(400)
        log.debug { "----> Loading country with code[$code] and caching in redis ..." }
        return Country(code)
    }

    /**
     * [code] 에 대한 Redis-backed cached country entry 를 evict 합니다.
     *
     * @throws IllegalArgumentException [code] 가 비어 있을 때 발생합니다.
     */
    @CacheEvict(key = "'country:' + #code")
    fun evictCache(code: String) {
        code.requireNotBlank("code")
        log.debug { "Evict country cache. code=$code" }
    }
}
