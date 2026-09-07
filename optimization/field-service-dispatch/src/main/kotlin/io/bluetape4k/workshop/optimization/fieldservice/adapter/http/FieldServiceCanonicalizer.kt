package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.jackson3.CanonicalJson
import io.bluetape4k.jackson3.CanonicalJsonLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigest
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigestMatch
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceEvents
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/**
 * Event digest 전에 JSON 입력을 닫힌 canonical 표현으로 정규화합니다.
 *
 * strict duplicate/trailing token 검사와 body/depth/name/output 제한은 공용
 * [CanonicalJson]에 위임하고, event digest 타입과 오류 변환은 이 경계가 소유합니다.
 */
class FieldServiceCanonicalizer {
    private val canonicalJson = CanonicalJson(
        limits = CanonicalJsonLimits(
            maxBodyBytes = FieldServiceLimits.MAX_BODY_BYTES,
            maxDepth = FieldServiceLimits.MAX_JSON_DEPTH,
            maxStringLength = FieldServiceLimits.MAX_STRING_LENGTH,
            maxNameLength = FieldServiceLimits.MAX_KEY_LENGTH,
            maxObjectEntries = FieldServiceLimits.MAX_BODY_BYTES,
            maxArrayElements = FieldServiceLimits.MAX_BODY_BYTES,
            maxOutputBytes = FieldServiceLimits.MAX_BODY_BYTES,
        ),
    )

    fun canonicalBytes(body: ByteArray): ByteArray {
        if (body.isEmpty() || body.size > FieldServiceLimits.MAX_BODY_BYTES) {
            throw InvalidFieldServiceInput("JSON body must be 1..${FieldServiceLimits.MAX_BODY_BYTES} bytes")
        }
        return try {
            canonicalJson.canonicalBytes(body)
        } catch (failure: Exception) {
            throw InvalidFieldServiceInput("invalid canonical JSON", failure)
        }
    }

    fun digest(body: ByteArray): EventDigest =
        EventDigest(
            MessageDigest.getInstance("SHA-256")
                .digest(canonicalBytes(body))
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) },
        )

    fun compareStoredDigest(stored: EventDigest, incoming: EventDigest): EventDigestMatch =
        FieldServiceEvents.compare(stored, incoming)
}
