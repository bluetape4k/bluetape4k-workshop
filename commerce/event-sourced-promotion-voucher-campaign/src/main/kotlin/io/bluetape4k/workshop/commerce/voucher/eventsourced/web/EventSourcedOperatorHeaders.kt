package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireNotNull
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.operations.RebuildRequestIdentity
import org.springframework.http.HttpHeaders

/**
 * `EXPECTED_GENERATION_TOKEN_HEADER`은 바우처·프로모션에서 낙관적 동시성과 이벤트·메시지 재생 순서를 검증하는 버전 값입니다.
 *
 * 기대값과 비교해 오래된 명령·중복 이벤트·잘못된 재생을 거절합니다.
 */
internal const val EXPECTED_GENERATION_TOKEN_HEADER = "X-Expected-Generation-Token"

/**
 * operatorIdentity은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun HttpHeaders.operatorIdentity(): RebuildRequestIdentity =
    RebuildRequestIdentity(
        tenant = getFirst(TENANT_HEADER).requireNotBlank(TENANT_HEADER),
        principal = getFirst(PRINCIPAL_HEADER).requireNotBlank(PRINCIPAL_HEADER),
        idempotencyKey = getFirst(IDEMPOTENCY_HEADER).requireNotBlank(IDEMPOTENCY_HEADER),
    )

/**
 * expectedGenerationToken은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된
 * 응답으로 매핑합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun HttpHeaders.expectedGenerationToken(): Long =
    getFirst(EXPECTED_GENERATION_TOKEN_HEADER)
        .requireNotNull(EXPECTED_GENERATION_TOKEN_HEADER)
        .toLongOrNull()
        .requireNotNull(EXPECTED_GENERATION_TOKEN_HEADER)
        .requirePositiveNumber(EXPECTED_GENERATION_TOKEN_HEADER)
