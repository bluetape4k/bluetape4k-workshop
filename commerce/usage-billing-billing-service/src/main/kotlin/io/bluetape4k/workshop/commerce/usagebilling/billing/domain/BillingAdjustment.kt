package io.bluetape4k.workshop.commerce.usagebilling.billing.domain

import io.bluetape4k.support.requireNegativeNumber
import io.bluetape4k.support.requireNotBlank
import java.io.Serializable
import java.math.BigDecimal
import java.util.*
/**
 * BillingAdjustmentCommand은 사용량 계량·청구에서 전달되는 불변 데이터 모델입니다.
 * @property adjustmentEventId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property tenantId 사용량 계량·청구 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게 합니다.
 * @property correctionOf 사용량 계량·청구 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property amount 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 * @property currency 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 */


data class BillingAdjustmentCommand(
    /**
     * `adjustmentEventId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val adjustmentEventId: UUID,
    /**
     * `tenantId`은 사용량 과금·정산에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `correctionOf`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val correctionOf: UUID,
    /**
     * `amount`은 사용량 과금·정산에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val amount: BigDecimal,
    /**
     * `currency`은 사용량 과금·정산에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val currency: String,
): Serializable {
    init {
        tenantId.requireNotBlank("tenantId")
        currency.requireNotBlank("currency")
        amount.requireNegativeNumber("amount")
    }

    private companion object {
        /**
         * `serialVersionUID`은 사용량 과금·정산에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * `BillingAdjustmentOutcome`은 처리 결과입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
enum class BillingAdjustmentOutcome {
    /**
     * `BillingAdjustmentOutcome.APPLIED`은 마이그레이션·명령·outbox 반영이 이번 호출에서 실제로 완료된 값입니다.
     */
    APPLIED,
    /**
     * `BillingAdjustmentOutcome.DUPLICATE`은 동일한 요청 또는 이벤트가 이미 반영되어 멱등하게 무시해야 하는 값입니다.
     */
    DUPLICATE,
}

/**
 * BillingAdjustmentJournal은 사용량 과금 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
interface BillingAdjustmentJournal {
    /**
     * post은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param command 사용량 계량·청구 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
    fun post(command: BillingAdjustmentCommand): BillingAdjustmentOutcome
}
