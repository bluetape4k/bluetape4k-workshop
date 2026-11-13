package io.bluetape4k.workshop.cache.redis.domain

import io.bluetape4k.support.randomString
import io.bluetape4k.support.toUtf8Bytes
import java.io.Serializable

/**
 * Country 정보 - Redis에 캐시합니다.
 *
 * @property code Country code
 */
data class Country(
    val code: String,
): Serializable {

    val payloadText = randomString(1024)
    val payloadBytes = randomString(1024).toUtf8Bytes()
}
