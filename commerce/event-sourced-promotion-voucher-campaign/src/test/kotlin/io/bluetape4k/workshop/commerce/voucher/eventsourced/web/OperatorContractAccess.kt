package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.shared.voucher.NormalizedVoucherCampaignResult
import io.bluetape4k.workshop.shared.voucher.NormalizedVoucherAllocationResult
import io.bluetape4k.workshop.shared.voucher.VoucherAllocationBlackBoxRequest
import io.bluetape4k.workshop.shared.voucher.VoucherCampaignActivationRequest
import io.bluetape4k.workshop.shared.voucher.VoucherCampaignBlackBoxRequest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

internal class OperatorContractAccess private constructor(
    val origin: String,
    val secret: String,
    val guard: String,
) {
    companion object {
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
    val response = exchange().expectStatus().isEqualTo(expected.status)
    expected.replayed?.let { response.expectHeader().valueEquals("Idempotency-Replayed", it.toString()) }
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
    val response = exchange().expectStatus().isEqualTo(expected.status)
    expected.replayed?.let { response.expectHeader().valueEquals("Idempotency-Replayed", it.toString()) }
    val body = response.expectBody()
    expected.code?.let { body.jsonPath("$.code").isEqualTo(it) }
    expected.state?.let { body.jsonPath("$.state").isEqualTo(it) }
    expected.revision?.let { body.jsonPath("$.revision").isEqualTo(it) }
    expected.policyVersion?.let { body.jsonPath("$.policyVersion").isEqualTo(it) }
    if (expected.hasCode == true) {
        body.jsonPath("$.code").isNotEmpty
    }
}
