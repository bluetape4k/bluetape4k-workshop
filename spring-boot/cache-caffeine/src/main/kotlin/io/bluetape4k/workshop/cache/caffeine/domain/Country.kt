package io.bluetape4k.workshop.cache.caffeine.domain

import io.bluetape4k.support.randomString
import java.io.Serializable

/**
 * Country 정보 - 캐시에 저장합니다.
 *
 * @property code Country code
 */
data class Country(
    val code: String,
): Serializable {

    val payloadText: String = randomString(1024)
    val payloadBytes: ByteArray = randomString(1024).toByteArray()
}
