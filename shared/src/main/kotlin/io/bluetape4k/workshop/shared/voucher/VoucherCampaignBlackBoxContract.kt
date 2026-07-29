package io.bluetape4k.workshop.shared.voucher

import io.bluetape4k.support.requireGt
import io.bluetape4k.support.requireEquals
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.time.Instant
import java.util.UUID

/**
 * normalized-state 예제와 event-sourced voucher 예제가 공유하는 표준 요청이다.
 *
 * 각 모듈은 HTTP adapter만 소유한다. 시나리오와 normalized result vocabulary는
 * 이곳에 두어 한 모듈의 test fixture를 복사하는 방식으로 compatibility를 주장하지 못하게 한다.
 */
class VoucherCampaignBlackBoxRequest private constructor(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
    val campaignId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
) {
    companion object {
        /**
         * 검증된 black-box campaign request를 만든다.
         *
         * caller가 제공한 identity, interval, limit 값은 공통 bluetape4k
         * [IllegalArgumentException] validation contract를 그대로 따른다.
         */
        operator fun invoke(
            tenant: String,
            principal: String,
            idempotencyKey: String,
            campaignId: UUID,
            startsAt: Instant,
            endsAt: Instant,
            capacity: Int,
            perUserLimit: Int,
            redemptionTtlSeconds: Long,
        ): VoucherCampaignBlackBoxRequest =
            VoucherCampaignBlackBoxRequest(
                tenant = tenant.requireNotBlank("tenant"),
                principal = principal.requireNotBlank("principal"),
                idempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey"),
                campaignId = campaignId,
                startsAt = startsAt,
                endsAt = endsAt.requireGt(startsAt, "endsAt"),
                capacity = capacity.requirePositiveNumber("capacity"),
                perUserLimit = perUserLimit.requirePositiveNumber("perUserLimit"),
                redemptionTtlSeconds = redemptionTtlSeconds.requirePositiveNumber("redemptionTtlSeconds"),
            )
    }
}

/**
 * 모든 compatibility adapter가 검증하는 backend-neutral campaign result다.
 */
class NormalizedVoucherCampaignResult private constructor(
    val status: Int,
    val code: String?,
    val campaignId: UUID?,
    val state: String?,
    val revision: Long?,
    val policyVersion: Long?,
    val capacity: Int?,
    val remainingCapacity: Int?,
    val replayed: Boolean?,
) {
    companion object {
        operator fun invoke(
            status: Int,
            code: String?,
            campaignId: UUID?,
            state: String?,
            revision: Long?,
            policyVersion: Long?,
            capacity: Int?,
            remainingCapacity: Int?,
            replayed: Boolean?,
        ): NormalizedVoucherCampaignResult =
            NormalizedVoucherCampaignResult(
                status = status.requireInRange(100, 599, "status"),
                code = code?.requireNotBlank("code"),
                campaignId = campaignId,
                state = state?.requireNotBlank("state"),
                revision = revision?.requireZeroOrPositiveNumber("revision"),
                policyVersion = policyVersion?.requireZeroOrPositiveNumber("policyVersion"),
                capacity = capacity?.requireZeroOrPositiveNumber("capacity"),
                remainingCapacity = remainingCapacity?.requireZeroOrPositiveNumber("remainingCapacity"),
                replayed = replayed,
            )
    }
}

/**
 * 각 모듈의 HTTP adapter를 통해 실행하는 공유 request/response scenario다.
 */
class VoucherCampaignBlackBoxScenario private constructor(
    val name: String,
    val first: VoucherCampaignBlackBoxRequest,
    val replay: VoucherCampaignBlackBoxRequest? = null,
    val expectedFirst: NormalizedVoucherCampaignResult,
    val expectedReplay: NormalizedVoucherCampaignResult? = null,
) {
    companion object {
        operator fun invoke(
            name: String,
            first: VoucherCampaignBlackBoxRequest,
            replay: VoucherCampaignBlackBoxRequest? = null,
            expectedFirst: NormalizedVoucherCampaignResult,
            expectedReplay: NormalizedVoucherCampaignResult? = null,
        ): VoucherCampaignBlackBoxScenario =
            VoucherCampaignBlackBoxScenario(
                name = name.requireNotBlank("name"),
                first = first,
                replay = replay,
                expectedFirst = expectedFirst,
                expectedReplay = expectedReplay,
            )
    }
}

/**
 * 두 HTTP adapter가 공유하는 표준 campaign activation request다.
 */
class VoucherCampaignActivationRequest private constructor(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
    val campaignId: UUID,
    val expectedRevision: Long,
) {
    companion object {
        operator fun invoke(
            tenant: String,
            principal: String,
            idempotencyKey: String,
            campaignId: UUID,
            expectedRevision: Long,
        ): VoucherCampaignActivationRequest =
            VoucherCampaignActivationRequest(
                tenant = tenant.requireNotBlank("tenant"),
                principal = principal.requireNotBlank("principal"),
                idempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey"),
                campaignId = campaignId,
                expectedRevision = expectedRevision.requireZeroOrPositiveNumber("expectedRevision"),
            )
    }
}

/**
 * 공유 create-then-activate flow다. replay는 두 번째 activation event를 append하면 안 된다.
 */
class VoucherCampaignActivationScenario private constructor(
    val name: String,
    val create: VoucherCampaignBlackBoxRequest,
    val expectedCreate: NormalizedVoucherCampaignResult,
    val first: VoucherCampaignActivationRequest,
    val replay: VoucherCampaignActivationRequest,
    val expectedFirst: NormalizedVoucherCampaignResult,
    val expectedReplay: NormalizedVoucherCampaignResult,
) {
    companion object {
        operator fun invoke(
            name: String,
            create: VoucherCampaignBlackBoxRequest,
            expectedCreate: NormalizedVoucherCampaignResult,
            first: VoucherCampaignActivationRequest,
            replay: VoucherCampaignActivationRequest,
            expectedFirst: NormalizedVoucherCampaignResult,
            expectedReplay: NormalizedVoucherCampaignResult,
        ): VoucherCampaignActivationScenario =
            VoucherCampaignActivationScenario(
                name = name.requireNotBlank("name"),
                create = create,
                expectedCreate = expectedCreate,
                first = first,
                replay = replay,
                expectedFirst = expectedFirst,
                expectedReplay = expectedReplay,
            )
    }
}

/**
 * 두 HTTP adapter가 공유하는 표준 voucher allocation request다.
 */
class VoucherAllocationBlackBoxRequest private constructor(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
    val campaignId: UUID,
    val userRef: String,
) {
    companion object {
        operator fun invoke(
            tenant: String,
            principal: String,
            idempotencyKey: String,
            campaignId: UUID,
            userRef: String,
        ): VoucherAllocationBlackBoxRequest {
            val validPrincipal = principal.requireNotBlank("principal")
            val validUserRef =
                userRef
                    .requireNotBlank("userRef")
                    .requireEquals(validPrincipal, "userRef")
            return VoucherAllocationBlackBoxRequest(
                tenant = tenant.requireNotBlank("tenant"),
                principal = validPrincipal,
                idempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey"),
                campaignId = campaignId,
                userRef = validUserRef,
            )
        }
    }
}

/**
 * backend-neutral voucher allocation result다.
 */
class NormalizedVoucherAllocationResult private constructor(
    val status: Int,
    val code: String?,
    val state: String?,
    val revision: Long?,
    val policyVersion: Long?,
    val hasCode: Boolean?,
    val replayed: Boolean?,
) {
    companion object {
        operator fun invoke(
            status: Int,
            code: String?,
            state: String?,
            revision: Long?,
            policyVersion: Long?,
            hasCode: Boolean?,
            replayed: Boolean?,
        ): NormalizedVoucherAllocationResult =
            NormalizedVoucherAllocationResult(
                status = status.requireInRange(100, 599, "status"),
                code = code?.requireNotBlank("code"),
                state = state?.requireNotBlank("state"),
                revision = revision?.requireZeroOrPositiveNumber("revision"),
                policyVersion = policyVersion?.requireZeroOrPositiveNumber("policyVersion"),
                hasCode = hasCode,
                replayed = replayed,
            )
    }
}

/**
 * 각 voucher implementation이 실행하는 공유 allocate-then-replay flow다.
 *
 * 첫 요청은 voucher 하나를 allocate해야 하며, replay는 capacity를 소비하거나
 * 다른 code를 발급하지 않고 같은 normalized outcome을 반환해야 한다.
 */
class VoucherAllocationBlackBoxScenario private constructor(
    val name: String,
    val campaign: VoucherCampaignActivationScenario,
    val first: VoucherAllocationBlackBoxRequest,
    val replay: VoucherAllocationBlackBoxRequest,
    val expectedFirst: NormalizedVoucherAllocationResult,
    val expectedReplay: NormalizedVoucherAllocationResult,
) {
    companion object {
        operator fun invoke(
            name: String,
            campaign: VoucherCampaignActivationScenario,
            first: VoucherAllocationBlackBoxRequest,
            replay: VoucherAllocationBlackBoxRequest,
            expectedFirst: NormalizedVoucherAllocationResult,
            expectedReplay: NormalizedVoucherAllocationResult,
        ): VoucherAllocationBlackBoxScenario =
            VoucherAllocationBlackBoxScenario(
                name = name.requireNotBlank("name"),
                campaign = campaign,
                first = first,
                replay = replay,
                expectedFirst = expectedFirst,
                expectedReplay = expectedReplay,
            )
    }
}

/**
 * 공유 allocation 이후 검증하는 customer-visible voucher transition이다.
 */
enum class VoucherLifecycleAction {
    REDEEM,
    RELEASE,
}

/**
 * redeem/release compatibility check에 쓰는 backend-neutral result다.
 */
class NormalizedVoucherLifecycleResult private constructor(
    val status: Int,
    val code: String?,
    val state: String?,
    val revision: Long?,
    val policyVersion: Long?,
    val replayed: Boolean?,
) {
    companion object {
        operator fun invoke(
            status: Int,
            code: String?,
            state: String?,
            revision: Long?,
            policyVersion: Long?,
            replayed: Boolean?,
        ): NormalizedVoucherLifecycleResult =
            NormalizedVoucherLifecycleResult(
                status = status.requireInRange(100, 599, "status"),
                code = code?.requireNotBlank("code"),
                state = state?.requireNotBlank("state"),
                revision = revision?.requireZeroOrPositiveNumber("revision"),
                policyVersion = policyVersion?.requireZeroOrPositiveNumber("policyVersion"),
                replayed = replayed,
            )
    }
}

/**
 * 공유 allocation-to-transition scenario다. adapter는 자신의 allocation endpoint가
 * 반환한 generated claim identity와 one-time code를 제공한다.
 */
class VoucherLifecycleBlackBoxScenario private constructor(
    val name: String,
    val allocation: VoucherAllocationBlackBoxScenario,
    val action: VoucherLifecycleAction,
    val transitionIdempotencyKey: String,
    val redemptionReference: String?,
    val expectedFirst: NormalizedVoucherLifecycleResult,
    val expectedReplay: NormalizedVoucherLifecycleResult,
) {
    companion object {
        operator fun invoke(
            name: String,
            allocation: VoucherAllocationBlackBoxScenario,
            action: VoucherLifecycleAction,
            transitionIdempotencyKey: String,
            redemptionReference: String? = null,
            expectedFirst: NormalizedVoucherLifecycleResult,
            expectedReplay: NormalizedVoucherLifecycleResult,
        ): VoucherLifecycleBlackBoxScenario =
            VoucherLifecycleBlackBoxScenario(
                name = name.requireNotBlank("name"),
                allocation = allocation,
                action = action,
                transitionIdempotencyKey =
                    transitionIdempotencyKey.requireNotBlank("transitionIdempotencyKey"),
                redemptionReference = redemptionReference?.requireNotBlank("redemptionReference"),
                expectedFirst = expectedFirst,
                expectedReplay = expectedReplay,
            )
    }
}

/**
 * optional campaign setup과 거부 요청 전 successful warm-up allocation을 포함하는
 * 공유 allocation rejection scenario다.
 */
class VoucherAllocationFailureBlackBoxScenario private constructor(
    val name: String,
    val campaign: VoucherCampaignActivationScenario?,
    val activateCampaign: Boolean,
    val warmupRequests: List<VoucherAllocationBlackBoxRequest>,
    val failureRequest: VoucherAllocationBlackBoxRequest,
    val expectedFailure: NormalizedVoucherAllocationResult,
) {
    companion object {
        operator fun invoke(
            name: String,
            campaign: VoucherCampaignActivationScenario?,
            activateCampaign: Boolean,
            warmupRequests: List<VoucherAllocationBlackBoxRequest> = emptyList(),
            failureRequest: VoucherAllocationBlackBoxRequest,
            expectedFailure: NormalizedVoucherAllocationResult,
        ): VoucherAllocationFailureBlackBoxScenario =
            VoucherAllocationFailureBlackBoxScenario(
                name = name.requireNotBlank("name"),
                campaign = campaign,
                activateCampaign = activateCampaign,
                warmupRequests = warmupRequests.toList(),
                failureRequest = failureRequest,
                expectedFailure = expectedFailure,
            )
    }
}

/**
 * persistence detail을 노출하지 않고 두 구현이 같은 customer lifecycle failure를
 * 거부한다는 점을 증명하는 input mutation이다.
 */
enum class VoucherLifecycleFailureKind {
    WRONG_CODE,
    STALE_REVISION,
    OTHER_PRINCIPAL,
}

/**
 * fresh allocation 이후 실행하는 공유 redeem rejection scenario다.
 */
class VoucherLifecycleFailureBlackBoxScenario private constructor(
    val name: String,
    val allocation: VoucherAllocationBlackBoxScenario,
    val kind: VoucherLifecycleFailureKind,
    val idempotencyKey: String,
    val expectedFailure: NormalizedVoucherLifecycleResult,
) {
    companion object {
        operator fun invoke(
            name: String,
            allocation: VoucherAllocationBlackBoxScenario,
            kind: VoucherLifecycleFailureKind,
            idempotencyKey: String,
            expectedFailure: NormalizedVoucherLifecycleResult,
        ): VoucherLifecycleFailureBlackBoxScenario =
            VoucherLifecycleFailureBlackBoxScenario(
                name = name.requireNotBlank("name"),
                allocation = allocation,
                kind = kind,
                idempotencyKey = idempotencyKey.requireNotBlank("idempotencyKey"),
                expectedFailure = expectedFailure,
            )
    }
}

object VoucherCampaignBlackBoxContract {
    private const val TENANT = "voucher-contract"
    private const val PRINCIPAL = "voucher-contract-operator"
    private const val CAPACITY = 20
    private val STARTS_AT: Instant = Instant.parse("2026-07-22T00:00:00Z")
    private val ENDS_AT: Instant = Instant.parse("2026-07-31T00:00:00Z")

    val createAndReplay: VoucherCampaignBlackBoxScenario =
        VoucherCampaignBlackBoxScenario(
            name = "campaign create and same-key replay",
            first = request("contract-create-001"),
            replay = request("contract-create-001"),
            expectedFirst = created(replayed = false),
            expectedReplay = created(replayed = true),
        )

    val fingerprintConflict: VoucherCampaignBlackBoxScenario =
        VoucherCampaignBlackBoxScenario(
            name = "same key with a different request",
            first = request("contract-conflict-001"),
            replay = request("contract-conflict-001", capacity = CAPACITY + 1),
            expectedFirst = created(replayed = false),
            expectedReplay =
                NormalizedVoucherCampaignResult(
                    status = 409,
                    code = "IDEMPOTENCY_FINGERPRINT_CONFLICT",
                    campaignId = null,
                    state = null,
                    revision = null,
                    policyVersion = null,
                    capacity = null,
                    remainingCapacity = null,
                    replayed = null,
                ),
        )

    val scenarios: List<VoucherCampaignBlackBoxScenario> =
        listOf(createAndReplay, fingerprintConflict)

    val activateAndReplay: VoucherCampaignActivationScenario =
        activationScenario("contract-activate-create-001", "contract-activate-001")

    val allocateAndReplay: VoucherAllocationBlackBoxScenario =
        allocationScenario(
            createKey = "contract-allocate-create-001",
            activationKey = "contract-allocate-activate-001",
            allocationKey = "contract-allocate-001",
        )

    val lifecycleScenarios: List<VoucherLifecycleBlackBoxScenario> =
        listOf(
            VoucherLifecycleBlackBoxScenario(
                name = "voucher redeem and same-key replay",
                allocation =
                    allocationScenario(
                        createKey = "contract-redeem-create-001",
                        activationKey = "contract-redeem-activate-001",
                        allocationKey = "contract-redeem-allocate-001",
                    ),
                action = VoucherLifecycleAction.REDEEM,
                transitionIdempotencyKey = "contract-redeem-001",
                redemptionReference = "contract-order-001",
                expectedFirst = transitioned("REDEEMED", replayed = false),
                expectedReplay = transitioned("REDEEMED", replayed = true),
            ),
            VoucherLifecycleBlackBoxScenario(
                name = "voucher release and same-key replay",
                allocation =
                    allocationScenario(
                        createKey = "contract-release-create-001",
                        activationKey = "contract-release-activate-001",
                        allocationKey = "contract-release-allocate-001",
                    ),
                action = VoucherLifecycleAction.RELEASE,
                transitionIdempotencyKey = "contract-release-001",
                expectedFirst = transitioned("RELEASED", replayed = false),
                expectedReplay = transitioned("RELEASED", replayed = true),
            ),
        )

    val allocationFailures: List<VoucherAllocationFailureBlackBoxScenario> =
        listOf(
            draftAllocationFailure(),
            missingCampaignAllocationFailure(),
            perUserLimitAllocationFailure(),
        )

    val lifecycleFailures: List<VoucherLifecycleFailureBlackBoxScenario> =
        listOf(
            lifecycleFailure(
                slug = "wrong-code",
                kind = VoucherLifecycleFailureKind.WRONG_CODE,
                status = 409,
                code = "INVALID_CODE",
            ),
            lifecycleFailure(
                slug = "stale-revision",
                kind = VoucherLifecycleFailureKind.STALE_REVISION,
                status = 412,
                code = "STALE_REVISION",
            ),
            lifecycleFailure(
                slug = "other-principal",
                kind = VoucherLifecycleFailureKind.OTHER_PRINCIPAL,
                status = 404,
                code = "CLAIM_NOT_FOUND",
            ),
        )

    private fun allocationScenario(
        createKey: String,
        activationKey: String,
        allocationKey: String,
    ): VoucherAllocationBlackBoxScenario =
        activationScenario(createKey, activationKey).let { campaign ->
            val request =
                VoucherAllocationBlackBoxRequest(
                    tenant = campaign.create.tenant,
                    principal = "contract-customer",
                    idempotencyKey = allocationKey,
                    campaignId = campaign.create.campaignId,
                    userRef = "contract-customer",
                )
            VoucherAllocationBlackBoxScenario(
                name = "voucher allocate and same-key replay",
                campaign = campaign,
                first = request,
                replay = request,
                expectedFirst = allocated(replayed = false),
                expectedReplay = allocated(replayed = true),
            )
        }

    private fun draftAllocationFailure(): VoucherAllocationFailureBlackBoxScenario {
        val campaign = activationScenario("contract-draft-create-001", "contract-draft-activate-001")
        return VoucherAllocationFailureBlackBoxScenario(
            name = "draft campaign rejects allocation",
            campaign = campaign,
            activateCampaign = false,
            failureRequest =
                allocationRequest(
                    campaign = campaign,
                    principal = "contract-draft-customer",
                    key = "contract-draft-allocate-001",
                ),
            expectedFailure = allocationFailure(409, "CAMPAIGN_NOT_ACTIVE"),
        )
    }

    private fun missingCampaignAllocationFailure(): VoucherAllocationFailureBlackBoxScenario {
        val campaignId = UUID.nameUUIDFromBytes("contract-missing-campaign".toByteArray())
        return VoucherAllocationFailureBlackBoxScenario(
            name = "missing campaign rejects allocation without leaking storage details",
            campaign = null,
            activateCampaign = false,
            failureRequest =
                VoucherAllocationBlackBoxRequest(
                    tenant = TENANT,
                    principal = "contract-missing-customer",
                    idempotencyKey = "contract-missing-allocate-001",
                    campaignId = campaignId,
                    userRef = "contract-missing-customer",
                ),
            expectedFailure = allocationFailure(404, "CAMPAIGN_NOT_FOUND"),
        )
    }

    private fun perUserLimitAllocationFailure(): VoucherAllocationFailureBlackBoxScenario {
        val campaign =
            activationScenario(
                "contract-limit-create-001",
                "contract-limit-activate-001",
            )
        val principal = "contract-limit-customer"
        return VoucherAllocationFailureBlackBoxScenario(
            name = "third allocation for one subject exceeds the campaign limit",
            campaign = campaign,
            activateCampaign = true,
            warmupRequests =
                listOf(
                    allocationRequest(campaign, principal, "contract-limit-allocate-001"),
                    allocationRequest(campaign, principal, "contract-limit-allocate-002"),
                ),
            failureRequest =
                allocationRequest(campaign, principal, "contract-limit-allocate-003"),
            expectedFailure = allocationFailure(409, "PER_USER_LIMIT_REACHED"),
        )
    }

    private fun lifecycleFailure(
        slug: String,
        kind: VoucherLifecycleFailureKind,
        status: Int,
        code: String,
    ): VoucherLifecycleFailureBlackBoxScenario =
        VoucherLifecycleFailureBlackBoxScenario(
            name = "voucher redeem rejects $slug",
            allocation =
                allocationScenario(
                    createKey = "contract-$slug-create-001",
                    activationKey = "contract-$slug-activate-001",
                    allocationKey = "contract-$slug-allocate-001",
                ),
            kind = kind,
            idempotencyKey = "contract-$slug-redeem-001",
            expectedFailure =
                NormalizedVoucherLifecycleResult(
                    status = status,
                    code = code,
                    state = null,
                    revision = null,
                    policyVersion = null,
                    replayed = null,
                ),
        )

    private fun allocationRequest(
        campaign: VoucherCampaignActivationScenario,
        principal: String,
        key: String,
    ): VoucherAllocationBlackBoxRequest =
        VoucherAllocationBlackBoxRequest(
            tenant = campaign.create.tenant,
            principal = principal,
            idempotencyKey = key,
            campaignId = campaign.create.campaignId,
            userRef = principal,
        )

    private fun activationScenario(
        createKey: String,
        activationKey: String,
    ): VoucherCampaignActivationScenario =
        request(createKey).let { create ->
            val activation =
                VoucherCampaignActivationRequest(
                    tenant = create.tenant,
                    principal = create.principal,
                    idempotencyKey = activationKey,
                    campaignId = create.campaignId,
                    expectedRevision = 0,
                )
            VoucherCampaignActivationScenario(
                name = "campaign activate and same-key replay",
                create = create,
                expectedCreate = created(replayed = false),
                first = activation,
                replay = activation,
                expectedFirst = activated(replayed = false),
                expectedReplay = activated(replayed = true),
            )
        }

    private fun request(
        key: String,
        capacity: Int = CAPACITY,
    ): VoucherCampaignBlackBoxRequest =
        VoucherCampaignBlackBoxRequest(
            tenant = TENANT,
            principal = PRINCIPAL,
            idempotencyKey = key,
            campaignId = UUID.nameUUIDFromBytes(key.toByteArray()),
            startsAt = STARTS_AT,
            endsAt = ENDS_AT,
            capacity = capacity,
            perUserLimit = 2,
            redemptionTtlSeconds = 3_600,
        )

    private fun created(replayed: Boolean): NormalizedVoucherCampaignResult =
        NormalizedVoucherCampaignResult(
            status = 201,
            code = null,
            campaignId = null,
            state = "DRAFT",
            revision = 0,
            policyVersion = 0,
            capacity = CAPACITY,
            remainingCapacity = CAPACITY,
            replayed = replayed,
        )

    private fun activated(replayed: Boolean): NormalizedVoucherCampaignResult =
        NormalizedVoucherCampaignResult(
            status = 200,
            code = null,
            campaignId = null,
            state = "ACTIVE",
            revision = 1,
            policyVersion = 0,
            capacity = CAPACITY,
            remainingCapacity = CAPACITY,
            replayed = replayed,
        )

    private fun allocated(replayed: Boolean): NormalizedVoucherAllocationResult =
        NormalizedVoucherAllocationResult(
            status = 201,
            code = null,
            state = "ALLOCATED",
            revision = 0,
            policyVersion = 0,
            hasCode = true,
            replayed = replayed,
        )

    private fun allocationFailure(
        status: Int,
        code: String,
    ): NormalizedVoucherAllocationResult =
        NormalizedVoucherAllocationResult(
            status = status,
            code = code,
            state = null,
            revision = null,
            policyVersion = null,
            hasCode = null,
            replayed = null,
        )

    private fun transitioned(
        state: String,
        replayed: Boolean,
    ): NormalizedVoucherLifecycleResult =
        NormalizedVoucherLifecycleResult(
            status = 200,
            code = null,
            state = state,
            revision = 1,
            policyVersion = 0,
            replayed = replayed,
        )

}
