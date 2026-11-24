package io.bluetape4k.workshop.commerce.shared.integration

import io.bluetape4k.io.digest.Digesters

/**
 * commerce integration payload가 사용하는 lowercase SHA-256 checksum 계약이다.
 *
 * 이 값은 accidental corruption과 contract drift를 감지하는 checksum이며
 * authentication이나 caller trust를 제공하지 않는다. 적대적 변조에는 MAC 또는
 * signature가 필요하다.
 */
object PayloadDigest {

    /** UTF-8 payload를 canonical lowercase 64-character hexadecimal digest로 만든다. */
    fun of(payload: String): String = Digesters.SHA256.digestHexUtf8(payload)

    /** payload와 digest가 provider의 canonical hexadecimal 표현으로 일치하는지 확인한다. */
    fun matches(payload: String, digest: String): Boolean =
        Digesters.SHA256.matchesHexUtf8(payload, digest)
}

/**
 * tenant와 aggregate identity를 Kafka partition 및 local outbox에서 공유하는
 * deterministic key로 결합한다.
 */
object AggregatePartitionKey {

    /** 기존 `tenantId|aggregateType|aggregateId` 순서와 delimiter를 유지한다. */
    fun of(tenantId: String, aggregateType: String, aggregateId: String): String =
        "$tenantId|$aggregateType|$aggregateId"
}
