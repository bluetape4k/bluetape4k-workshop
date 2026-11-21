package io.bluetape4k.workshop.commerce.voucher.fixture

import io.bluetape4k.workshop.commerce.voucher.persistence.AuditTable
import io.bluetape4k.workshop.commerce.voucher.persistence.CampaignTable
import io.bluetape4k.workshop.commerce.voucher.persistence.ClaimTable
import io.bluetape4k.workshop.commerce.voucher.persistence.EventInboxTable
import io.bluetape4k.workshop.commerce.voucher.persistence.ReviewTable
import io.bluetape4k.workshop.commerce.voucher.persistence.VoucherTransactionRunner
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service
/**
 * VoucherFixtureResetResult은 프로모션 바우처에서 전달되는 불변 데이터 모델입니다.
 * @property clearedSignals 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property deletedRows 프로모션 바우처 경계에서 전달되는 업무 내용입니다. 스키마·크기·무결성을 검증한 뒤 저장·발행하며 후속 상태 전이에 필요한 정보만
 * 포함합니다.
 */


internal data class VoucherFixtureResetResult(
    /**
     * `clearedSignals`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val clearedSignals: Int,
    /**
     * `deletedRows`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
     *
     * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
     */
    val deletedRows: Int,
)

/**
 * 선택된 workshop tenant만 삭제하며 production profile로 넘어가지 않습니다.
 * @property transactions 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property scenarios 프로모션 바우처 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@Service
@Profile("local", "demo", "test")
internal class VoucherFixtureResetService(
    /**
     * `transactions`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val transactions: VoucherTransactionRunner,
    /**
     * `scenarios`은 바우처·프로모션에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val scenarios: VoucherScenarioFixtures,
) {
    /**
     * reset은 프로모션 바우처의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param tenantId 프로모션 바우처 경계를 식별하는 값이며 다른 tenant 데이터와 섞이지 않도록 조회·변경 범위를 제한합니다.
     */
    fun reset(tenantId: String): VoucherFixtureResetResult =
        transactions.foregroundTransaction {
            /**
             * `deletedRows`은 바우처·프로모션에서 업무 집합 또는 조회 결과입니다.
             *
             * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
             */
            val deletedRows = ReviewTable.deleteWhere { ReviewTable.tenantId eq tenantId } +
                    AuditTable.deleteWhere { AuditTable.tenantId eq tenantId } +
                    EventInboxTable.deleteWhere { EventInboxTable.tenantId eq tenantId } +
                    ClaimTable.deleteWhere { ClaimTable.tenantId eq tenantId } +
                    CampaignTable.deleteWhere { CampaignTable.tenantId eq tenantId }

            VoucherFixtureResetResult(scenarios.reset(tenantId), deletedRows)
        }
}
