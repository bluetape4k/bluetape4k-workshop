package io.bluetape4k.workshop.commerce.ticket.salecontrol.internal

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.ticket.domain.SaleState
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketExposedJdbcRepository
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketJdbcTransaction
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketLockRank
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketSaleEntity
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketSalePolicies
import io.bluetape4k.workshop.commerce.ticket.persistence.TicketSales
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePolicySnapshot
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePurchaseAuthority
import io.bluetape4k.workshop.commerce.ticket.salecontrol.api.SalePurchasePolicy
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.io.Serial
import java.time.Instant
import java.util.*

/**
 * SaleAdmissionFailure은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
sealed class SaleAdmissionFailure(code: String): IllegalStateException(code) {
    companion object {
        /**
         * `serialVersionUID`은 콘서트 티켓 플래시 세일에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * SaleNotStarted은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class SaleNotStarted: SaleAdmissionFailure("sale_not_started")
/**
 * SaleClosed은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class SaleClosed: SaleAdmissionFailure("sale_closed")
/**
 * SaleNotOpen은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class SaleNotOpen: SaleAdmissionFailure("sale_not_open")
/**
 * StaleSalePolicy은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class StaleSalePolicy: SaleAdmissionFailure("stale_sale_policy")

/** Bluetape4k Exposed JDBC repository로 구현한 sale authority입니다. */
/** Bluetape4k Exposed JDBC repository로 구현한 sale authority입니다. */
class TicketSaleRepository: TicketExposedJdbcRepository<TicketSaleEntity, UUID>(TicketSaleEntity::class) {

    companion object: KLogging()
    /**
     * requireOpen은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param transaction 업무 처리에 필요한 transaction 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expected 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    fun requireOpen(
        transaction: TicketJdbcTransaction,
        expected: SalePolicySnapshot,
        now: Instant,
    ): SalePurchasePolicy = with(transaction) {
        acquire(TicketLockRank.SALE)
        /**
         * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */

        val sale = TicketSales.selectAll()
            .where { TicketSales.id eq expected.saleId }
            .forUpdate()
            .singleOrNull()
            ?: throw SaleClosed()
        /**
         * 동시성 제어와 이벤트 재생 순서를 나타내는 버전 값입니다. 기대값이 다르면 오래된 명령을 거부합니다.
         */

        val policyVersion = sale[TicketSales.currentPolicyVersion]

        TicketSalePolicies.selectAll()
            .where {
                (TicketSalePolicies.saleId eq expected.saleId) and
                        (TicketSalePolicies.policyVersion eq policyVersion)
            }
            .forUpdate()
            .singleOrNull()
            ?.let { policy ->
                val opensAt = sale[TicketSales.opensAt]
                val closesAt = sale[TicketSales.closesAt]
                if (expected.policyVersion != policyVersion) throw StaleSalePolicy()
                if (now.isBefore(expected.opensAt) || now.isBefore(opensAt)) throw SaleNotStarted()
                if (!now.isBefore(closesAt)) throw SaleClosed()
                if (sale[TicketSales.state] != SaleState.OPEN.code) throw SaleNotOpen()

                SalePurchasePolicy(
                    saleId = expected.saleId,
                    policyVersion = policyVersion,
                    perUserLimit = policy[TicketSalePolicies.perUserLimit],
                    maxQuantity = policy[TicketSalePolicies.maxQuantity],
                    holdSeconds = policy[TicketSalePolicies.holdSeconds],
                )
            }
            ?: throw SaleClosed()
    }
}

/**
 * purchase transaction 안에서 sale window와 immutable policy를 다시 검증합니다.
 * @property sales 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class SaleService(
    /**
     * `sales`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val sales: TicketSaleRepository = TicketSaleRepository(),
): SalePurchaseAuthority {

    companion object: KLogging()

    /**
     * requireOpen은 콘서트 티켓 판매 명령의 불변조건과 tenant·revision 범위를 부작용 전에 검증하여 잘못된 상태 전이와 부분 저장을 막습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param transaction 업무 처리에 필요한 transaction 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expected 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     * @param now 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    override fun requireOpen(
        transaction: TicketJdbcTransaction,
        expected: SalePolicySnapshot,
        now: Instant,
    ): SalePurchasePolicy =
        sales.requireOpen(transaction, expected, now)
}
