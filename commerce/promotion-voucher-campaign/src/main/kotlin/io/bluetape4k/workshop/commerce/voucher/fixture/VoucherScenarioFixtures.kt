package io.bluetape4k.workshop.commerce.voucher.fixture

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.voucher.application.RiskSignal
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * VoucherScenarioExpectation은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherScenarioExpectation(
    /**
     * `campaignState`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val campaignState: String,
    /**
     * `claimState`은 바우처·프로모션에서 허용된 상태 전이와 terminal 여부를 선택하는 생명주기 값입니다.
     *
     * 임의 문자열로 해석하지 말고 전이표·재시도·보상 규칙을 함께 적용합니다.
     */
    val claimState: String?,
    /**
     * `reviewOpen`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val reviewOpen: Boolean,
    /**
     * `durableAuthority`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val durableAuthority: String = "POSTGRESQL",
    /**
     * `auditReason`은 바우처·프로모션에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val auditReason: String,
    /**
     * `sseEvent`은 바우처·프로모션에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    val sseEvent: String,
)

/**
 * VoucherScenarioDefinition은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal data class VoucherScenarioDefinition(
    /**
     * `slug`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val slug: String,
    /**
     * `summary`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val summary: String,
    /**
     * `expected`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val expected: VoucherScenarioExpectation,
    /**
     * `riskSignal`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val riskSignal: RiskSignal? = null,
    /**
     * `riskOperation`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val riskOperation: FixtureRiskOperation? = null,
)

/**
 * FixtureRiskOperation은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal enum class FixtureRiskOperation {
    /**
     * `FixtureRiskOperation.ALLOCATION`은 FixtureRiskOperation 업무 상태의 `ALLOCATION` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    ALLOCATION,
    /**
     * `FixtureRiskOperation.REDEMPTION`은 FixtureRiskOperation 업무 상태의 `REDEMPTION` 단계입니다. 허용된 상태 전이와
     * 재시도·보상·응답 분기를 선택하는 기준으로 사용합니다.
     */
    REDEMPTION,
}

/** 보호된 fixture API와 browser console이 공유하는 닫힌 결정적 cookbook입니다. */
/** 보호된 fixture API와 browser console이 공유하는 닫힌 결정적 cookbook입니다. */
@Component
@Profile("local", "demo", "test")
internal class VoucherScenarioFixtures {

    /**
     * `configured`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val configured = ConcurrentHashMap<FixtureSubject, VoucherScenarioDefinition>()

    /**
     * catalog은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun catalog(): List<VoucherScenarioDefinition> = DEFINITIONS

    /**
     * definition은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param slug 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun definition(slug: String): VoucherScenarioDefinition =
        BY_SLUG[slug] ?: throw IllegalArgumentException("unsupported fixture scenario")

    /**
     * configure은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun configure(
        slug: String,
        tenantId: String,
        principalRef: String,
    ): VoucherScenarioDefinition {
        val definition = definition(slug)
        if (definition.riskOperation != null) {
            afterCommit {
                configured[FixtureSubject(tenantId, principalRef)] = definition
            }
        }
        return definition
    }

    /**
     * consumeRiskSignal은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun consumeRiskSignal(
        tenantId: String,
        principalRef: String,
        operation: FixtureRiskOperation,
    ): RiskSignal? {
        val subject = FixtureSubject(tenantId, principalRef)

        val definition = configured[subject] ?: return null
        if (definition.riskOperation != operation) return null

        configured.remove(subject, definition)
        return definition.riskSignal
    }

    /**
     * reset은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param tenantId 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
    fun reset(tenantId: String): Int {
        val signals = configured.filterKeys { it.tenantId == tenantId }
        afterCommit {
            signals.forEach { (subject, definition) ->
                configured.remove(subject, definition)
            }
        }
        return signals.size
    }

    /** process-local demo signal을 감싸는 PostgreSQL transaction과 일관되게 유지합니다. */
    /** process-local demo signal을 감싸는 PostgreSQL transaction과 일관되게 유지합니다. */
    private inline fun afterCommit(crossinline action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }

        TransactionSynchronizationManager.registerSynchronization(
            object: TransactionSynchronization {
                /**
                 * afterCommit은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
                 *
                 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
                 */
                override fun afterCommit() = action()
            },
        )
    }

    /**
     * FixtureSubject은 프로모션 바우처 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
     * 해석합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    private data class FixtureSubject(val tenantId: String, val principalRef: String)

    companion object: KLogging() {
        /**
         * `DEMO_TENANT`은 바우처·프로모션에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
         *
         * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
         */
        const val DEMO_TENANT = "voucher-demo"
        /**
         * `FIXED_TIME`은 바우처·프로모션에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
         *
         * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
         */
        val FIXED_TIME: Instant = Instant.parse("2026-07-20T00:00:00Z")

        /**
         * `DEFINITIONS`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val DEFINITIONS: List<VoucherScenarioDefinition> = listOf(
            scenario(
                "happy-allocation",
                "Allocation and redemption succeed",
                "ACTIVE",
                "REDEEMED",
                false,
                "VOUCHER_REDEEMED"
            ),
            scenario(
                "same-key-response-loss",
                "A lost response replays exactly",
                "ACTIVE",
                "ALLOCATED",
                false,
                "IDEMPOTENT_REPLAY"
            ),
            scenario(
                "capacity-race",
                "Concurrent allocation stops at capacity",
                "ACTIVE",
                "ALLOCATED",
                false,
                "CAPACITY_EXHAUSTED"
            ),
            scenario(
                "allocation-review",
                "Allocation enters operator review",
                "ACTIVE",
                "PENDING_REVIEW",
                true,
                "ALLOCATION_REVIEW_OPENED",
                RiskSignal.REVIEW,
                FixtureRiskOperation.ALLOCATION
            ),
            scenario(
                "redemption-review",
                "Redemption enters operator review",
                "ACTIVE",
                "PENDING_REVIEW",
                true,
                "REDEMPTION_REVIEW_OPENED",
                RiskSignal.REVIEW,
                FixtureRiskOperation.REDEMPTION
            ),
            scenario(
                "pause-allocation-race",
                "Pause and allocation record one policy winner",
                "PAUSED",
                "ALLOCATED",
                false,
                "CAMPAIGN_PAUSED"
            ),
            scenario(
                "redeem-revoke-race",
                "Redeem and revoke record one terminal winner",
                "ACTIVE",
                "REDEEMED",
                false,
                "CLAIM_TERMINAL_RACE"
            ),
            scenario(
                "policy-change",
                "A stale policy revision fails closed",
                "ACTIVE",
                "ALLOCATED",
                false,
                "STALE_POLICY_VERSION"
            ),
            scenario(
                "redis-outage",
                "Redis loss falls back to PostgreSQL",
                "ACTIVE",
                "ALLOCATED",
                false,
                "REDIS_DEGRADED",
                RiskSignal.UNKNOWN,
                FixtureRiskOperation.ALLOCATION
            ),
            scenario(
                "bloom-false-positive",
                "Bloom positive opens review without terminal rejection",
                "ACTIVE",
                "PENDING_REVIEW",
                true,
                "BLOOM_REVIEW_OPENED",
                RiskSignal.REVIEW,
                FixtureRiskOperation.ALLOCATION
            ),
            scenario(
                "delayed-duplicate-out-of-order",
                "Delayed duplicates apply once and stale order conflicts",
                "ACTIVE",
                null,
                false,
                "DELAYED_EVENT_CONFLICT"
            ),
        )
        /**
         * `BY_SLUG`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
         *
         * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
         */
        private val BY_SLUG: Map<String, VoucherScenarioDefinition> =
            DEFINITIONS.associateBy(VoucherScenarioDefinition::slug)

        /**
         * scenario은 프로모션 바우처 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
         *
         * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
         */
        private fun scenario(
            slug: String,
            summary: String,
            campaignState: String,
            claimState: String?,
            reviewOpen: Boolean,
            auditReason: String,
            riskSignal: RiskSignal? = null,
            riskOperation: FixtureRiskOperation? = null,
        ): VoucherScenarioDefinition =
            VoucherScenarioDefinition(
                slug = slug,
                summary = summary,
                expected = VoucherScenarioExpectation(
                    campaignState = campaignState,
                    claimState = claimState,
                    reviewOpen = reviewOpen,
                    auditReason = auditReason,
                    sseEvent = "audit",
                ),
                riskSignal = riskSignal,
                riskOperation = riskOperation,
            )
    }
}
