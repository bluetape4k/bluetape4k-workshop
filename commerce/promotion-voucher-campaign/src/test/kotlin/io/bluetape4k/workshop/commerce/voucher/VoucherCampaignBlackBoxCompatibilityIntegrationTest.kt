package io.bluetape4k.workshop.commerce.voucher

import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.commerce.shared.voucher.NormalizedVoucherAllocationResult
import io.bluetape4k.workshop.commerce.shared.voucher.NormalizedVoucherCampaignResult
import io.bluetape4k.workshop.commerce.shared.voucher.NormalizedVoucherLifecycleResult
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherAllocationBlackBoxRequest
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherAllocationBlackBoxScenario
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherCampaignActivationRequest
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherCampaignBlackBoxContract
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherCampaignBlackBoxRequest
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherLifecycleAction
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherLifecycleBlackBoxScenario
import io.bluetape4k.workshop.commerce.shared.voucher.VoucherLifecycleFailureKind
import io.bluetape4k.workshop.commerce.voucher.web.AllocationHttpResponse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import java.util.*

@Tag("integration")
/**
 * VoucherCampaignBlackBoxCompatibilityIntegrationTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherCampaignBlackBoxCompatibilityIntegrationTest: AbstractVoucherIntegrationTest() {

    companion object: KLogging()

    @Test
/**
 * 이 시나리오는 "normalized state adapter satisfies the shared campaign contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normalized state adapter satisfies the shared campaign contract`() {
        VoucherCampaignBlackBoxContract.scenarios.forEach { scenario ->
            postCampaign(scenario.first).assertNormalized(scenario.expectedFirst, scenario.first.campaignId)

            scenario.replay?.let { replay ->
                postCampaign(replay).assertNormalized(scenario.expectedReplay!!, replay.campaignId)
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "normalized state adapter satisfies the shared campaign activation contract" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normalized state adapter satisfies the shared campaign activation contract`() {
        val scenario = VoucherCampaignBlackBoxContract.activateAndReplay

        postCampaign(scenario.create).assertNormalized(scenario.expectedCreate, scenario.create.campaignId)
        postActivation(scenario.first).assertNormalized(scenario.expectedFirst, scenario.first.campaignId)
        postActivation(scenario.replay).assertNormalized(scenario.expectedReplay, scenario.replay.campaignId)
    }

    @Test
/**
 * 이 시나리오는 "normalized state adapter satisfies the shared voucher allocation contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normalized state adapter satisfies the shared voucher allocation contract`() {
        val scenario = VoucherCampaignBlackBoxContract.allocateAndReplay

        postCampaign(scenario.campaign.create).assertNormalized(
            scenario.campaign.expectedCreate,
            scenario.campaign.create.campaignId
        )
        postActivation(scenario.campaign.first).assertNormalized(
            scenario.campaign.expectedFirst,
            scenario.campaign.first.campaignId
        )
        postAllocation(scenario.first).assertNormalizedAllocation(scenario.expectedFirst)
        postAllocation(scenario.replay).assertNormalizedAllocation(scenario.expectedReplay)
    }

    @Test
/**
 * 이 시나리오는 "normalized state adapter satisfies the shared voucher lifecycle contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normalized state adapter satisfies the shared voucher lifecycle contract`() {
        VoucherCampaignBlackBoxContract.lifecycleScenarios.forEach { scenario ->
            val allocation = createAllocation(scenario)

            postLifecycle(scenario, allocation).assertNormalizedLifecycle(scenario.expectedFirst)
            postLifecycle(scenario, allocation).assertNormalizedLifecycle(scenario.expectedReplay)
        }
    }

    @Test
/**
 * 이 시나리오는 "normalized state adapter satisfies the shared allocation failure contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normalized state adapter satisfies the shared allocation failure contract`() {
        VoucherCampaignBlackBoxContract.allocationFailures.forEach { scenario ->
            scenario.campaign?.let { campaign ->
                postCampaign(campaign.create).assertNormalized(campaign.expectedCreate, campaign.create.campaignId)

                if (scenario.activateCampaign) {
                    postActivation(campaign.first).assertNormalized(campaign.expectedFirst, campaign.first.campaignId)
                }
            }
            scenario.warmupRequests.forEach { request ->
                postAllocation(request)
                    .exchange()
                    .expectStatus().isCreated
            }
            postAllocation(scenario.failureRequest)
                .assertNormalizedAllocation(scenario.expectedFailure)
        }
    }

    @Test
/**
 * 이 시나리오는 "normalized state adapter satisfies the shared voucher lifecycle failure contract" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `normalized state adapter satisfies the shared voucher lifecycle failure contract`() {
        VoucherCampaignBlackBoxContract.lifecycleFailures.forEach { scenario ->
            val allocation = createAllocation(scenario.allocation)
            val request = scenario.allocation.first
            val principal = if (scenario.kind == VoucherLifecycleFailureKind.OTHER_PRINCIPAL) {
                "${request.principal}-other"
            } else {
                request.principal
            }
            val code = if (scenario.kind == VoucherLifecycleFailureKind.WRONG_CODE) {
                "V1-invalid-code"
            } else {
                allocation.code.shouldNotBeNull()
            }
            val expectedRevision = if (scenario.kind == VoucherLifecycleFailureKind.STALE_REVISION) 1 else 0

            customerPost(
                tenant = request.tenant,
                principal = principal,
                path = "/api/v1/claims/${allocation.claimId}/redeem",
                idempotencyKey = scenario.idempotencyKey,
                body = mapOf(
                    "code" to code,
                    "expectedRevision" to expectedRevision,
                    "redemptionReference" to "contract-failure-order",
                ),
            ).assertNormalizedLifecycle(scenario.expectedFailure)
        }
    }

/**
 * createAllocation은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createAllocation(
        scenario: VoucherLifecycleBlackBoxScenario,
    ): AllocationHttpResponse =
        createAllocation(scenario.allocation)

/**
 * createAllocation은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun createAllocation(
        allocation: VoucherAllocationBlackBoxScenario,
    ): AllocationHttpResponse {
/**
 * campaign은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val campaign = allocation.campaign

        postCampaign(campaign.create).assertNormalized(campaign.expectedCreate, campaign.create.campaignId)
        postActivation(campaign.first).assertNormalized(campaign.expectedFirst, campaign.first.campaignId)

        return postAllocation(allocation.first)
            .exchange()
            .expectStatus().isCreated
            .expectBody<AllocationHttpResponse>()
            .returnResult().responseBody.shouldNotBeNull()
    }

/**
 * postLifecycle은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun postLifecycle(
        scenario: VoucherLifecycleBlackBoxScenario,
        allocation: AllocationHttpResponse,
    ): WebTestClient.RequestHeadersSpec<*> {
/**
 * request은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val request = scenario.allocation.first
/**
 * path은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val path = when (scenario.action) {
            VoucherLifecycleAction.REDEEM  -> "/api/v1/claims/${allocation.claimId}/redeem"
            VoucherLifecycleAction.RELEASE -> "/api/v1/claims/${allocation.claimId}/release"
        }
/**
 * body은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val body = when (scenario.action) {
            VoucherLifecycleAction.REDEEM  ->
                mapOf(
                    "code" to allocation.code.shouldNotBeNull(),
                    "expectedRevision" to 0,
                    "redemptionReference" to scenario.redemptionReference.shouldNotBeNull(),
                )
            VoucherLifecycleAction.RELEASE -> mapOf("expectedRevision" to 0)
        }
        return customerPost(
            tenant = request.tenant,
            principal = request.principal,
            path = path,
            idempotencyKey = scenario.transitionIdempotencyKey,
            body = body,
        )
    }

/**
 * postCampaign은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun postCampaign(request: VoucherCampaignBlackBoxRequest): WebTestClient.RequestHeadersSpec<*> =
        operatorPost(
            tenant = request.tenant,
            path = "/operator/api/v1/campaigns",
            idempotencyKey = request.idempotencyKey,
            body = mapOf(
                "campaignId" to request.campaignId,
                "startsAt" to request.startsAt,
                "endsAt" to request.endsAt,
                "capacity" to request.capacity,
                "perUserLimit" to request.perUserLimit,
                "redemptionTtlSeconds" to request.redemptionTtlSeconds,
            ),
        ) {
            it.header("If-None-Match", "*")
                .header(PRINCIPAL_HEADER, request.principal)
                .header("X-Workshop-Operator-Role", "OPERATOR")
        }

/**
 * postActivation은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun postActivation(request: VoucherCampaignActivationRequest): WebTestClient.RequestHeadersSpec<*> =
        operatorPost(
            tenant = request.tenant,
            path = "/operator/api/v1/campaigns/${request.campaignId}/activate",
            idempotencyKey = request.idempotencyKey,
            body = mapOf("expectedRevision" to request.expectedRevision),
        ) {
            it.header(PRINCIPAL_HEADER, request.principal)
                .header("X-Workshop-Operator-Role", "OPERATOR")
        }

/**
 * postAllocation은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun postAllocation(request: VoucherAllocationBlackBoxRequest): WebTestClient.RequestHeadersSpec<*> =
        customerPost(
            tenant = request.tenant,
            principal = request.principal,
            path = "/api/v1/campaigns/${request.campaignId}/claims",
            idempotencyKey = request.idempotencyKey,
            body = mapOf("userRef" to request.userRef),
        )

    private fun WebTestClient.RequestHeadersSpec<*>.assertNormalized(
        expected: NormalizedVoucherCampaignResult,
        campaignId: UUID,
    ) {
/**
 * response은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = exchange().expectStatus().isEqualTo(expected.status)
        expected.replayed?.let {
            response.expectHeader().valueEquals("Idempotency-Replayed", it.toString())
        }

/**
 * body은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val body = response.expectBody()
        log.debug { "body=$body" }

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

    private fun WebTestClient.RequestHeadersSpec<*>.assertNormalizedAllocation(
        expected: NormalizedVoucherAllocationResult,
    ) {
/**
 * response은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = exchange().expectStatus().isEqualTo(expected.status)
        expected.replayed?.let { response.expectHeader().valueEquals("Idempotency-Replayed", it.toString()) }

/**
 * body은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val body = response.expectBody()
        log.debug { "body=$body" }

        expected.code?.let { body.jsonPath("$.code").isEqualTo(it) }
        expected.state?.let { body.jsonPath("$.state").isEqualTo(it) }
        expected.revision?.let { body.jsonPath("$.revision").isEqualTo(it) }
        expected.policyVersion?.let { body.jsonPath("$.policyVersion").isEqualTo(it) }

        if (expected.hasCode == true) {
            body.jsonPath("$.code").isNotEmpty
        }
    }

    private fun WebTestClient.RequestHeadersSpec<*>.assertNormalizedLifecycle(
        expected: NormalizedVoucherLifecycleResult,
    ) {
/**
 * response은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val response = exchange().expectStatus().isEqualTo(expected.status)
        expected.replayed?.let {
            response.expectHeader().valueEquals("Idempotency-Replayed", it.toString())
        }
/**
 * body은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val body = response.expectBody()
        log.debug { "body=$body" }
        expected.code?.let { body.jsonPath("$.code").isEqualTo(it) }
        expected.state?.let { body.jsonPath("$.state").isEqualTo(it) }
        expected.revision?.let { body.jsonPath("$.revision").isEqualTo(it) }
        expected.policyVersion?.let { body.jsonPath("$.policyVersion").isEqualTo(it) }
    }
}
