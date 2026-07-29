package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.RebuildRequestIdentity
import org.springframework.http.HttpHeaders

internal const val EXPECTED_GENERATION_TOKEN_HEADER = "X-Expected-Generation-Token"

internal fun HttpHeaders.operatorIdentity(): RebuildRequestIdentity =
    RebuildRequestIdentity(
        tenant = getFirst(TENANT_HEADER).requireNotNull(TENANT_HEADER).requireNotBlank(TENANT_HEADER),
        principal = getFirst(PRINCIPAL_HEADER).requireNotNull(PRINCIPAL_HEADER).requireNotBlank(PRINCIPAL_HEADER),
        idempotencyKey =
            getFirst(IDEMPOTENCY_HEADER).requireNotNull(IDEMPOTENCY_HEADER).requireNotBlank(IDEMPOTENCY_HEADER),
    )

internal fun HttpHeaders.expectedGenerationToken(): Long =
    getFirst(EXPECTED_GENERATION_TOKEN_HEADER)
        .requireNotNull(EXPECTED_GENERATION_TOKEN_HEADER)
        .toLongOrNull()
        .requireNotNull(EXPECTED_GENERATION_TOKEN_HEADER)
        .requirePositiveNumber(EXPECTED_GENERATION_TOKEN_HEADER)
