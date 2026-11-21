package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.shared.voucher.NormalizedVoucherCampaignResult
import io.bluetape4k.workshop.commerce.shared.voucher.NormalizedVoucherAllocationResult
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherAllocationBlackBoxRequest
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherCampaignActivationRequest
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherCampaignBlackBoxRequest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

/**
 * OperatorContractAccess 테스트 묶음은 이벤트 기반 프로모션 바우처 캠페인의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class OperatorContractAccess private constructor(
/**
 * origin은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val origin: String,
/**
 * secret은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val secret: String,
/**
 * guard은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val guard: String,
) {
    companion object: KLogging() {
/**
 * invoke은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
        operator fun invoke(
            origin: String,
            secret: String,
            guard: String,
        ): OperatorContractAccess =
            OperatorContractAccess(
                origin = origin.requireNotBlank("origin"),
                secret = secret.requireNotBlank("secret"),
                guard = guard.requireNotBlank("guard"),
            )
    }
}

/**
 * postCampaignContract은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun WebTestClient.postCampaignContract(
    request: VoucherCampaignBlackBoxRequest,
    access: OperatorContractAccess,
): WebTestClient.RequestHeadersSpec<*> =
    post()
        .uri("/operator/api/v1/campaigns")
        .contentType(MediaType.APPLICATION_JSON)
        .header(TENANT_HEADER, request.tenant)
        .header(PRINCIPAL_HEADER, request.principal)
        .header(IDEMPOTENCY_HEADER, request.idempotencyKey)
        .header("If-None-Match", "*")
        .header(OPERATOR_SECRET_HEADER, access.secret)
        .header(OPERATOR_GUARD_HEADER, access.guard)
        .header(OPERATOR_ROLE_HEADER, "OPERATOR")
        .header("Origin", access.origin)
        .bodyValue(
            mapOf(
                "campaignId" to request.campaignId,
                "startsAt" to request.startsAt,
                "endsAt" to request.endsAt,
                "capacity" to request.capacity,
                "perUserLimit" to request.perUserLimit,
                "redemptionTtlSeconds" to request.redemptionTtlSeconds,
            ),
        )

/**
 * postCampaignActivationContract은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun WebTestClient.postCampaignActivationContract(
    request: VoucherCampaignActivationRequest,
    access: OperatorContractAccess,
): WebTestClient.RequestHeadersSpec<*> =
    post()
        .uri("/operator/api/v1/campaigns/${request.campaignId}/activate")
        .contentType(MediaType.APPLICATION_JSON)
        .header(TENANT_HEADER, request.tenant)
        .header(PRINCIPAL_HEADER, request.principal)
        .header(IDEMPOTENCY_HEADER, request.idempotencyKey)
        .header(OPERATOR_SECRET_HEADER, access.secret)
        .header(OPERATOR_GUARD_HEADER, access.guard)
        .header(OPERATOR_ROLE_HEADER, "OPERATOR")
        .header("Origin", access.origin)
        .bodyValue(mapOf("expectedRevision" to request.expectedRevision))

/**
 * postVoucherAllocationContract은 이벤트 기반 프로모션 바우처 캠페인 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
internal fun WebTestClient.postVoucherAllocationContract(
    request: VoucherAllocationBlackBoxRequest,
): WebTestClient.RequestHeadersSpec<*> =
    post()
        .uri("/api/v1/campaigns/${request.campaignId}/claims")
        .contentType(MediaType.APPLICATION_JSON)
        .header(TENANT_HEADER, request.tenant)
        .header(PRINCIPAL_HEADER, request.principal)
        .header(IDEMPOTENCY_HEADER, request.idempotencyKey)
        .bodyValue(mapOf("userRef" to request.userRef))

internal fun WebTestClient.RequestHeadersSpec<*>.assertNormalizedCampaign(
    expected: NormalizedVoucherCampaignResult,
    campaignId: UUID,
) {
/**
 * response은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val response = exchange().expectStatus().isEqualTo(expected.status)
    expected.replayed?.let { response.expectHeader().valueEquals("Idempotency-Replayed", it.toString()) }
/**
 * body은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val body = response.expectBody()

    expected.code?.let { body.jsonPath("$.code").isEqualTo(it) }
    expected.state?.let { body.jsonPath("$.state").isEqualTo(it) }
    expected.revision?.let { body.jsonPath("$.revision").isEqualTo(it) }
    expected.policyVersion?.let { body.jsonPath("$.policyVersion").isEqualTo(it) }
    expected.capacity?.let { body.jsonPath("$.capacity").isEqualTo(it) }
    expected.remainingCapacity?.let { body.jsonPath("$.remainingCapacity").isEqualTo(it) }
    if (expected.campaignId != null || expected.state != null) {
        body.jsonPath("$.campaignId").isEqualTo(campaignId.toString())
    }
}

internal fun WebTestClient.RequestHeadersSpec<*>.assertNormalizedAllocation(
    expected: NormalizedVoucherAllocationResult,
) {
/**
 * response은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val response = exchange().expectStatus().isEqualTo(expected.status)
    expected.replayed?.let { response.expectHeader().valueEquals("Idempotency-Replayed", it.toString()) }
/**
 * body은 이벤트 기반 프로모션 바우처 캠페인 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    val body = response.expectBody()

    expected.code?.let { body.jsonPath("$.code").isEqualTo(it) }
    expected.state?.let { body.jsonPath("$.state").isEqualTo(it) }
    expected.revision?.let { body.jsonPath("$.revision").isEqualTo(it) }
    expected.policyVersion?.let { body.jsonPath("$.policyVersion").isEqualTo(it) }

    if (expected.hasCode == true) {
        body.jsonPath("$.code").isNotEmpty
    }
}
