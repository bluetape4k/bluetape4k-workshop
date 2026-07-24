package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank

private const val MIN_IDEMPOTENCY_KEY_LENGTH = 8
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200

internal fun String.validCommandIdentity(name: String): String = requireNotBlank(name)

internal fun String.validIdempotencyKey(): String {
    val validKey = requireNotBlank("Idempotency-Key")
    val validLength =
        validKey.length.requireInRange(
            MIN_IDEMPOTENCY_KEY_LENGTH,
            MAX_IDEMPOTENCY_KEY_LENGTH,
            "Idempotency-Key.length",
        )
    return validKey.take(validLength)
}
