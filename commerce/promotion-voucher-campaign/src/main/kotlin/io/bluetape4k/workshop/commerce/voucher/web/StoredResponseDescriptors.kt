package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.support.requireNotNull
import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import java.time.Instant
import java.util.*

/** raw JSON을 저장하지 않고 원래 HTTP body를 재현하는 데 사용하는 닫힌 bounded metadata입니다. */
/** raw JSON을 저장하지 않고 원래 HTTP body를 재현하는 데 사용하는 닫힌 bounded metadata입니다. */
internal fun CampaignSnapshot.storedCampaignResponse(
    kind: VoucherResponseKind,
    status: Int,
    publicHeaders: Map<String, String> = emptyMap(),
): StoredHttpResponse =
    StoredHttpResponse(
        responseKind = kind,
        status = status,
        headers = publicHeaders + (CAMPAIGN_DESCRIPTOR_HEADER to campaignDescriptor()),
        aggregateId = campaignId,
        allocationId = null,
        aggregateRevision = revision,
        generationKeyVersion = null,
        verificationKeyVersion = null,
    )

/**
 * storedClaimResponse은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param publicHeaders 업무 처리에 필요한 publicHeaders 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param allocationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param generationKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 * @param verificationKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
internal fun ClaimSnapshot.storedClaimResponse(
    kind: VoucherResponseKind,
    status: Int = 200,
    publicHeaders: Map<String, String> = emptyMap(),
    allocationId: UUID? = null,
    generationKeyVersion: Int? = null,
    verificationKeyVersion: Int? = null,
): StoredHttpResponse =
    StoredHttpResponse(
        responseKind = kind,
        status = status,
        headers = publicHeaders + (CLAIM_DESCRIPTOR_HEADER to claimDescriptor()),
        aggregateId = claimId,
        allocationId = allocationId,
        aggregateRevision = revision,
        generationKeyVersion = generationKeyVersion,
        verificationKeyVersion = verificationKeyVersion,
    )

/**
 * storedAllocationResponse은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
 * 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param kind 업무 처리에 필요한 kind 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
 * @param allocationId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param reviewId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 * @param generationKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 * @param verificationKeyVersion 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
internal fun ClaimSnapshot.storedAllocationResponse(
    kind: VoucherResponseKind,
    status: Int,
    allocationId: UUID,
    reviewId: Long?,
    generationKeyVersion: Int?,
    verificationKeyVersion: Int?,
): StoredHttpResponse =
    StoredHttpResponse(
        responseKind = kind,
        status = status,
        headers = mapOf(
            "Location" to "/api/v1/claims/$claimId",
            ALLOCATION_DESCRIPTOR_HEADER to allocationDescriptor(reviewId),
        ),
        aggregateId = claimId,
        allocationId = allocationId,
        aggregateRevision = revision,
        generationKeyVersion = generationKeyVersion,
        verificationKeyVersion = verificationKeyVersion,
    )

/**
 * campaignBody은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun StoredHttpResponse.campaignBody(): CampaignHttpResponse {
    val values = descriptor(CAMPAIGN_DESCRIPTOR_HEADER, 8)
    return CampaignHttpResponse(
        campaignId = aggregateId,
        state = values[0],
        revision = values[1].toLong(),
        policyVersion = values[2].toLong(),
        capacity = values[3].toInt(),
        allocatedCount = values[4].toInt(),
        remainingCapacity = (values[3].toInt() - values[4].toInt()).coerceAtLeast(0),
        startsAt = Instant.ofEpochMilli(values[5].toLong()),
        endsAt = Instant.ofEpochMilli(values[6].toLong()),
        observedAt = Instant.ofEpochMilli(values[7].toLong()),
    )
}

/**
 * claimBody은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun StoredHttpResponse.claimBody(): ClaimHttpResponse {
    val values = descriptor(CLAIM_DESCRIPTOR_HEADER, 5)
    return ClaimHttpResponse(
        campaignId = UUID.fromString(values[0]),
        claimId = aggregateId,
        state = values[1],
        revision = values[2].toLong(),
        policyVersion = values[3].toLong(),
        expiresAt = values[4].instantOrNull(),
    )
}

/**
 * allocationBody은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param code 업무 처리에 필요한 code 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
internal fun StoredHttpResponse.allocationBody(code: String?): AllocationHttpResponse {
    val values = descriptor(ALLOCATION_DESCRIPTOR_HEADER, 5)
    return AllocationHttpResponse(
        claimId = aggregateId,
        state = values[0],
        revision = values[1].toLong(),
        policyVersion = values[2].toLong(),
        expiresAt = values[3].instantOrNull(),
        reviewId = values[4].longOrNull(),
        code = code,
    )
}

/**
 * isStoredDescriptorHeader은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를
 * 함께 보존합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
internal fun String.isStoredDescriptorHeader(): Boolean =
    this == CAMPAIGN_DESCRIPTOR_HEADER ||
            this == CLAIM_DESCRIPTOR_HEADER ||
            this == ALLOCATION_DESCRIPTOR_HEADER ||
            this == RECONCILIATION_DESCRIPTOR_HEADER ||
            this == "X-Workshop-Fixture-Evidence" ||
            this == "X-Workshop-Fixture-Reset-Result"

/**
 * campaignDescriptor은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
 * 보존합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun CampaignSnapshot.campaignDescriptor(): String =
    listOf(
        state.name,
        revision,
        policyVersion,
        capacity,
        allocatedCount,
        startsAt.toEpochMilli(),
        endsAt.toEpochMilli(),
        Instant.now().toEpochMilli(),
    ).joinToString(DESCRIPTOR_SEPARATOR)

/**
 * claimDescriptor은 프로모션 바우처의 동시 작업에서 한 실행자만 처리하도록 lease·lock을 원자적으로 획득·갱신하고 만료·소유자 불일치를 거절합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun ClaimSnapshot.claimDescriptor(): String =
    listOf(
        campaignId,
        state.name,
        revision,
        allocationPolicyVersion,
        expiresAt?.toEpochMilli() ?: NULL_VALUE
    ).joinToString(DESCRIPTOR_SEPARATOR)

/**
 * allocationDescriptor은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께
 * 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param reviewId 처리할 프로모션 바우처 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
 */
private fun ClaimSnapshot.allocationDescriptor(reviewId: Long?): String =
    listOf(
        state.name,
        revision,
        allocationPolicyVersion,
        expiresAt?.toEpochMilli() ?: NULL_VALUE,
        reviewId ?: NULL_VALUE,
    ).joinToString(DESCRIPTOR_SEPARATOR)

/**
 * descriptor은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param header 업무 처리에 필요한 header 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 * @param expectedSize 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
 */
private fun StoredHttpResponse.descriptor(
    header: String,
    expectedSize: Int,
): List<String> =
    headers[header]
        .requireNotNull { "stored response descriptor is missing" }
        .split(DESCRIPTOR_SEPARATOR)
        .also {
            require(it.size == expectedSize) {
                "stored response descriptor is invalid"
            }
        }

/**
 * instantOrNull은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun String.instantOrNull(): Instant? =
    takeUnless { it == NULL_VALUE }?.toLong()?.let(Instant::ofEpochMilli)

/**
 * longOrNull은 프로모션 바우처 저장소를 transaction 경계 안에서 변경하며 idempotency key·revision·감사 필드를 함께 보존합니다.
 *
 * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
 */
private fun String.longOrNull(): Long? =
    takeUnless { it == NULL_VALUE }?.toLong()

/**
 * `RECONCILIATION_DESCRIPTOR_HEADER`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
internal const val RECONCILIATION_DESCRIPTOR_HEADER = "X-Workshop-Reconciliation-Result"
/**
 * `CAMPAIGN_DESCRIPTOR_HEADER`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
 *
 * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
 */
private const val CAMPAIGN_DESCRIPTOR_HEADER = "X-Workshop-Campaign-Descriptor"
/**
 * `CLAIM_DESCRIPTOR_HEADER`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
 *
 * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
 */
private const val CLAIM_DESCRIPTOR_HEADER = "X-Workshop-Claim-Descriptor"
/**
 * `ALLOCATION_DESCRIPTOR_HEADER`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val ALLOCATION_DESCRIPTOR_HEADER = "X-Workshop-Allocation-Descriptor"
/**
 * `DESCRIPTOR_SEPARATOR`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
 *
 * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
 */
private const val DESCRIPTOR_SEPARATOR = "|"
/**
 * `NULL_VALUE`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
 *
 * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
 */
private const val NULL_VALUE = "-"
