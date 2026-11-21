package io.bluetape4k.workshop.commerce.order.domain

import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.util.*

/**
 * `OrderStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class OrderStatus {
    /**
     * `OrderStatus.SUBMITTED`은 요청이 접수되어 입력 검증과 후속 처리를 기다리는 값입니다.
     */
    SUBMITTED,
    /**
     * `OrderStatus.ACCEPTED`은 업무 정책 검증을 통과하여 다음 처리 단계로 진행할 수 있는 값입니다.
     */
    ACCEPTED,
    /**
     * `OrderStatus.FULFILLMENT_IN_PROGRESS`은 주문 이행 작업이 진행 중이며 완료 또는 취소 전이만 허용되는 값입니다.
     */
    FULFILLMENT_IN_PROGRESS,
    /**
     * `OrderStatus.COMPLETED`은 업무 결과가 확정되어 정상 흐름이 종료된 값입니다.
     */
    COMPLETED,
    /**
     * `OrderStatus.CANCELLED`은 취소 요청과 필요한 보상 작업이 반영되어 더 이상 정상 진행하지 않는 값입니다.
     */
    CANCELLED
}

/**
 * `PaymentStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class PaymentStatus {
    /**
     * `PaymentStatus.CREATED`은 `created` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    CREATED,
    /**
     * `PaymentStatus.AUTHORIZING`은 결제 provider 승인 요청이 진행 중인 값입니다.
     */
    AUTHORIZING,
    /**
     * `PaymentStatus.SUCCEEDED`은 외부 provider 또는 내부 작업의 성공 결과가 확정된 값입니다.
     */
    SUCCEEDED,
    /**
     * `PaymentStatus.FAILED`은 처리가 실패했으며 재시도 가능 여부를 별도 정책으로 판단해야 하는 값입니다.
     */
    FAILED,
    /**
     * `PaymentStatus.CANCELLED`은 취소 요청과 필요한 보상 작업이 반영되어 더 이상 정상 진행하지 않는 값입니다.
     */
    CANCELLED
}

/**
 * `ReservationStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class ReservationStatus {
    /**
     * `ReservationStatus.HELD`은 재고나 예약 자원을 임시로 점유하고 확정 여부를 기다리는 값입니다.
     */
    HELD,
    /**
     * `ReservationStatus.COMMITTED`은 점유한 자원이 최종 확정되어 다른 요청에 재사용할 수 없는 값입니다.
     */
    COMMITTED,
    /**
     * `ReservationStatus.RELEASED`은 임시 점유가 해제되어 자원이 다시 사용 가능해진 값입니다.
     */
    RELEASED,
    /**
     * `ReservationStatus.EXPIRED`은 허용된 만료 시각이 지나 자동 종료된 값이며 재사용 전 검증이 필요합니다.
     */
    EXPIRED,
    /**
     * `ReservationStatus.RECONCILIATION_REQUIRED`은 provider 결과나 순서가 모호하여 자동 전이 대신 운영자 대사가 필요한 값입니다.
     */
    RECONCILIATION_REQUIRED
}

/**
 * `FulfillmentStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class FulfillmentStatus {
    /**
     * `FulfillmentStatus.REQUESTED`은 후속 처리 또는 외부 호출을 요청했지만 결과가 아직 확정되지 않은 값입니다.
     */
    REQUESTED,
    /**
     * `FulfillmentStatus.ALLOCATED`은 사용 가능한 자원이 특정 요청에 배정되어 다른 요청이 선점할 수 없는 값입니다.
     */
    ALLOCATED,
    /**
     * `FulfillmentStatus.PICKING`은 `picking` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    PICKING,
    /**
     * `FulfillmentStatus.SHIPPED`은 `shipped` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를 확인합니다.
     */
    SHIPPED,
    /**
     * `FulfillmentStatus.DELIVERED`은 `delivered` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    DELIVERED,
    /**
     * `FulfillmentStatus.CANCELLED`은 취소 요청과 필요한 보상 작업이 반영되어 더 이상 정상 진행하지 않는 값입니다.
     */
    CANCELLED
}

/**
 * `CancellationStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class CancellationStatus {
    /**
     * `CancellationStatus.REQUESTED`은 후속 처리 또는 외부 호출을 요청했지만 결과가 아직 확정되지 않은 값입니다.
     */
    REQUESTED,
    /**
     * `CancellationStatus.APPROVED`은 검토·정책 검증을 통과하여 다음 업무 단계를 허용하는 값입니다.
     */
    APPROVED,
    /**
     * `CancellationStatus.REJECTED`은 정책 또는 입력 검증을 통과하지 못해 요청을 수용하지 않은 값입니다.
     */
    REJECTED
}

/**
 * `RefundStatus`은 상태 코드입니다.
 * 저장·이벤트·API 응답에서 같은 이름을 사용하며, 허용되지 않은 상태 전이는 거부해야 합니다.
 */
internal enum class RefundStatus {
    /**
     * `RefundStatus.REQUESTED`은 후속 처리 또는 외부 호출을 요청했지만 결과가 아직 확정되지 않은 값입니다.
     */
    REQUESTED,
    /**
     * `RefundStatus.PENDING_PROVIDER`은 `pending provider` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와
     * terminal 여부를
     * 확인합니다.
     */
    PENDING_PROVIDER,
    /**
     * `RefundStatus.SUCCEEDED`은 외부 provider 또는 내부 작업의 성공 결과가 확정된 값입니다.
     */
    SUCCEEDED,
    /**
     * `RefundStatus.FAILED`은 처리가 실패했으며 재시도 가능 여부를 별도 정책으로 판단해야 하는 값입니다.
     */
    FAILED,
    /**
     * `RefundStatus.MANUAL_REVIEW`은 `manual review` 업무 상황을 나타냅니다. 현재 상태에서 허용된 다음 전이와 terminal 여부를
     * 확인합니다.
     */
    MANUAL_REVIEW
}

/**
 * `AggregateType`은 업무 대상 유형입니다.
 * aggregate·event·resource의 종류를 구분해 저장 schema와 routing 규칙을 결정합니다.
 */
internal enum class AggregateType {
    /**
     * `AggregateType.ORDER`은 `order` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를 결정합니다.
     */
    ORDER,
    /**
     * `AggregateType.PAYMENT_ATTEMPT`은 `payment attempt` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event
     * routing 키를
     * 결정합니다.
     */
    PAYMENT_ATTEMPT,
    /**
     * `AggregateType.INVENTORY_RESERVATION`은 `inventory reservation` 업무 상황을 나타냅니다. 이 분류는 저장
     * schema와 event
     * routing 키를 결정합니다.
     */
    INVENTORY_RESERVATION,
    /**
     * `AggregateType.FULFILLMENT_GROUP`은 `fulfillment group` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event
     * routing
     * 키를 결정합니다.
     */
    FULFILLMENT_GROUP,
    /**
     * `AggregateType.CANCELLATION_CASE`은 `cancellation case` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event
     * routing
     * 키를 결정합니다.
     */
    CANCELLATION_CASE,
    /**
     * `AggregateType.REFUND_CASE`은 `refund case` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event routing 키를
     * 결정합니다.
     */
    REFUND_CASE,
}

/**
 * `ProviderMode`은 동작 모드입니다.
 * provider 또는 실행 정책을 선택하는 값이며 테스트·운영 시나리오의 분기 기준입니다.
 */
internal enum class ProviderMode {
    /**
     * `ProviderMode.SUCCESS`은 `success` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를 선택하며 실제 업무 상태와 혼동하지
     * 않습니다.
     */
    SUCCESS,
    /**
     * `ProviderMode.DECLINE`은 `decline` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를 선택하며 실제 업무 상태와 혼동하지
     * 않습니다.
     */
    DECLINE,
    /**
     * `ProviderMode.DELAYED_SUCCESS`은 `delayed success` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를 선택하며 실제
     * 업무 상태와
     * 혼동하지 않습니다.
     */
    DELAYED_SUCCESS,
    /**
     * `ProviderMode.OUT_OF_ORDER`은 `out of order` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를 선택하며 실제 업무
     * 상태와 혼동하지
     * 않습니다.
     */
    OUT_OF_ORDER,
    /**
     * `ProviderMode.DUPLICATE_SUCCESS`은 `duplicate success` 업무 상황을 나타냅니다. provider 또는 실행 시나리오를
     * 선택하며 실제 업무
     * 상태와 혼동하지 않습니다.
     */
    DUPLICATE_SUCCESS
}

/**
 * `ProviderEventKind`은 분류 코드입니다.
 * aggregate·event·resource의 종류를 구분해 저장 schema와 routing 규칙을 결정합니다.
 */
internal enum class ProviderEventKind {
    /**
     * `ProviderEventKind.AUTHORIZING`은 결제 provider 승인 요청이 진행 중인 값입니다.
     */
    AUTHORIZING,
    /**
     * `ProviderEventKind.SUCCEEDED`은 외부 provider 또는 내부 작업의 성공 결과가 확정된 값입니다.
     */
    SUCCEEDED,
    /**
     * `ProviderEventKind.FAILED`은 처리가 실패했으며 재시도 가능 여부를 별도 정책으로 판단해야 하는 값입니다.
     */
    FAILED,
    /**
     * `ProviderEventKind.REFUND_SUCCEEDED`은 `refund succeeded` 업무 상황을 나타냅니다. 이 분류는 저장 schema와 event
     * routing 키를 결정합니다.
     */
    REFUND_SUCCEEDED,
    /**
     * `ProviderEventKind.REFUND_FAILED`은 업무 처리가 실패해 결과를 성공으로 간주할 수 없는 값입니다.
     */
    REFUND_FAILED
}

/**
 * `ProviderEventDisposition`은 후속 처리 판단입니다.
 * 외부 결과를 성공·재시도·보상·수동 대사로 분기할 때 사용하는 업무 계약입니다.
 */
internal enum class ProviderEventDisposition {
    /**
     * `ProviderEventDisposition.APPLIED`은 마이그레이션·명령·outbox 반영이 이번 호출에서 실제로 완료된 값입니다.
     */
    APPLIED,
    /**
     * `ProviderEventDisposition.DUPLICATE`은 동일한 요청 또는 이벤트가 이미 반영되어 멱등하게 무시해야 하는 값입니다.
     */
    DUPLICATE,
/**
 * `ProviderEventDisposition.CONFLICT`은 현재 revision 또는 업무 상태와 충돌하여 자동 반영할 수 없는 값입니다.
 *
 * 영속화·이벤트·API 응답에 노출될 때도 동일한 의미를 유지해야 하며, 이 값과 다른 상태로 되돌리는 경우에는 전용 보상 전이를 사용합니다.
 */
    CONFLICT,
    /**
     * `ProviderEventDisposition.IGNORED_OUT_OF_ORDER`은 오래된 이벤트라서 최신 상태를 덮어쓰지 않고 관찰 기록만 남기는 값입니다.
     */
    IGNORED_OUT_OF_ORDER,
    /**
     * `ProviderEventDisposition.UNRESOLVED`은 자동 판정으로 안전하게 결론낼 수 없어 격리·대사해야 하는 값입니다.
     */
    UNRESOLVED
}
/**
 * SubmitOrder은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property tenantId 주문·fulfillment 요청이 속한 테넌트 식별자입니다. 저장·이벤트·멱등성 범위를 이 값으로 분리해 다른 고객의 데이터가 섞이지 않게
 * 합니다.
 * @property customerReference 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야
 * 합니다.
 * @property lines 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property providerMode 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 */


internal data class SubmitOrder(
    /**
     * `tenantId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val tenantId: String,
    /**
     * `customerReference`은 주문·결제·재고·배송에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    val customerReference: String,
    /**
     * `lines`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val lines: List<SubmitOrderLine>,
    /**
     * `providerMode`은 주문·결제·재고·배송에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val providerMode: ProviderMode = ProviderMode.SUCCESS,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * SubmitOrderLine은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property sku 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property quantity 동시 처리량·할당량·집계 수량 또는 처리 진척도입니다. 음수·한도 초과를 검증하고 경쟁 요청에서도 원자적으로 갱신해야 합니다.
 * @property unitPrice 청구·정산에 사용되는 금액 또는 통화 값입니다. 반올림·통화 일치 규칙을 거친 뒤 저장하며 문자열 포맷만으로 계산하지 않습니다.
 */


internal data class SubmitOrderLine(
    /**
     * `sku`은 주문·결제·재고·배송에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    val sku: String,
    /**
     * `quantity`은 주문·결제·재고·배송에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val quantity: Int,
    /**
     * `unitPrice`은 주문·결제·재고·배송에서 한도·할당·정산·처리 진척을 계산하는 업무 수치입니다.
     *
     * 음수·상한·단위와 반올림 규칙을 검증하고 경쟁 요청에서도 원자적으로 갱신합니다.
     */
    val unitPrice: BigDecimal,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * OrderSubmitted은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property orderId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property paymentAttemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property providerMode 주문·fulfillment 업무 흐름을 구성하는 값입니다. 명령 검증과 영속화·이벤트 변환 시 이 값의 의미를 보존해야 합니다.
 * @property occurredAt 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */


internal data class OrderSubmitted(
    /**
     * `eventId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `orderId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId: UUID,
    /**
     * `paymentAttemptId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val paymentAttemptId: UUID,
    /**
     * `providerMode`은 주문·결제·재고·배송에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val providerMode: ProviderMode,
    /**
     * `occurredAt`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * PaymentProviderEvent은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property providerEventId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property paymentAttemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property kind 현재 주문·fulfillment 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로 해석하지
 * 않습니다.
 * @property occurredAt 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */


internal data class PaymentProviderEvent(
    /**
     * `providerEventId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val providerEventId: String,
    /**
     * `paymentAttemptId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val paymentAttemptId: UUID,
    /**
     * `kind`은 주문·결제·재고·배송에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val kind: ProviderEventKind,
    /**
     * `occurredAt`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * PaymentSucceeded은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property orderId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property paymentAttemptId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property occurredAt 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */


internal data class PaymentSucceeded(
    /**
     * `eventId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `orderId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId: UUID,
    /**
     * `paymentAttemptId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val paymentAttemptId: UUID,
    /**
     * `occurredAt`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * InventoryCommitted은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property orderId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property reservationId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property occurredAt 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */


internal data class InventoryCommitted(
    /**
     * `eventId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `orderId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId: UUID,
    /**
     * `reservationId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val reservationId: UUID,
    /**
     * `occurredAt`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}
/**
 * RefundRequested은 주문·fulfillment에서 전달되는 불변 데이터 모델입니다.
 * @property eventId 이벤트의 불변 식별자입니다. inbox/outbox 중복 제거와 감사 추적의 기준이므로 재시도에도 같은 값을 유지합니다.
 * @property orderId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property refundCaseId 업무 객체의 불변 식별자입니다. API·영속화·이벤트가 같은 대상을 가리키도록 생성 후 변경하지 않습니다.
 * @property reasonCode 현재 주문·fulfillment 처리의 분류 또는 수명주기 값입니다. 허용된 전이·재시도·오류 응답을 선택하는 기준이며 임의의 문자열로
 * 해석하지 않습니다.
 * @property occurredAt 주문·fulfillment의 업무 시각입니다. 만료·정렬·재시도·SLA 판정에 사용하므로 저장소와 API에서 동일한 시간대와 clock
 * 정책을 유지합니다.
 */


internal data class RefundRequested(
    /**
     * `eventId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val eventId: UUID,
    /**
     * `orderId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val orderId: UUID,
    /**
     * `refundCaseId`은 주문·결제·재고·배송에서 업무 대상의 불변 식별자입니다.
     *
     * 생성 후 변경하지 않으며 API·영속화·이벤트가 같은 대상을 가리키도록 유지합니다.
     */
    val refundCaseId: UUID,
    /**
     * `reasonCode`은 주문·결제·재고·배송에서 응답·재시도·보상·운영 분기를 선택하는 안정된 업무 코드입니다.
     *
     * 저장값과 외부 응답에서 의미를 바꾸지 말고 호출자가 처리할 수 있는 계약으로 유지합니다.
     */
    val reasonCode: String,
    /**
     * `occurredAt`은 주문·결제·재고·배송에서 만료·재시도·SLA·정산 경계를 계산하는 업무 시각 또는 기간입니다.
     *
     * clock과 timezone 정책을 고정하고 저장·이벤트·응답에서 같은 기준으로 해석합니다.
     */
    val occurredAt: Instant,
): Serializable {
    companion object {
        /**
         * `serialVersionUID`은 주문·결제·재고·배송에서 Java 직렬화 호환성을 유지하는 기술 메타데이터입니다.
         *
         * 도메인 상태가 아니므로 값을 바꾸거나 업무 식별자로 사용하지 않습니다.
         */
        private const val serialVersionUID: Long = 1L
    }
}

/**
 * InvalidTransition은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class InvalidTransition(
    aggregate: String,
    from: Enum<*>,
    to: Enum<*>,
): IllegalStateException("Invalid $aggregate transition: $from -> $to")

private fun <S: Enum<S>> transition(
    aggregate: String,
    from: S,
    to: S,
    allowed: Map<S, Set<S>>,
): S {
    if (to !in allowed.getOrDefault(from, emptySet())) {
        throw InvalidTransition(aggregate, from, to)
    }
    return to
}

/**
 * OrderPolicy은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object OrderPolicy {
    /**
     * 주문·fulfillment 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    private val allowed =
        mapOf(
            OrderStatus.SUBMITTED to setOf(OrderStatus.ACCEPTED, OrderStatus.CANCELLED),
            OrderStatus.ACCEPTED to setOf(OrderStatus.FULFILLMENT_IN_PROGRESS, OrderStatus.CANCELLED),
            OrderStatus.FULFILLMENT_IN_PROGRESS to setOf(OrderStatus.COMPLETED, OrderStatus.CANCELLED)
        )

    /**
     * transition은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        from: OrderStatus,
        to: OrderStatus,
    ) = transition("order", from, to, allowed)
}

/**
 * PaymentPolicy은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object PaymentPolicy {
    /**
     * 주문·fulfillment 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    private val allowed =
        mapOf(
            PaymentStatus.CREATED to setOf(PaymentStatus.AUTHORIZING, PaymentStatus.FAILED, PaymentStatus.CANCELLED),
            PaymentStatus.AUTHORIZING to setOf(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.CANCELLED)
        )

    /**
     * transition은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        from: PaymentStatus,
        to: PaymentStatus,
    ) = transition("payment", from, to, allowed)
}

/**
 * ReservationPolicy은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object ReservationPolicy {
    /**
     * 주문·fulfillment 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    private val allowed =
        mapOf(
            ReservationStatus.HELD to setOf(
                ReservationStatus.COMMITTED,
                ReservationStatus.RELEASED,
                ReservationStatus.EXPIRED,
                ReservationStatus.RECONCILIATION_REQUIRED
            ),
            ReservationStatus.RECONCILIATION_REQUIRED to setOf(
                ReservationStatus.COMMITTED,
                ReservationStatus.RELEASED
            )
        )

    /**
     * transition은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        from: ReservationStatus,
        to: ReservationStatus,
    ) = transition("reservation", from, to, allowed)
}

/**
 * FulfillmentPolicy은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object FulfillmentPolicy {
    /**
     * 주문·fulfillment 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    private val allowed =
        mapOf(
            FulfillmentStatus.REQUESTED to setOf(FulfillmentStatus.ALLOCATED, FulfillmentStatus.CANCELLED),
            FulfillmentStatus.ALLOCATED to setOf(FulfillmentStatus.PICKING, FulfillmentStatus.CANCELLED),
            FulfillmentStatus.PICKING to setOf(FulfillmentStatus.SHIPPED, FulfillmentStatus.CANCELLED),
            FulfillmentStatus.SHIPPED to setOf(FulfillmentStatus.DELIVERED)
        )

    /**
     * transition은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        from: FulfillmentStatus,
        to: FulfillmentStatus,
    ) = transition("fulfillment", from, to, allowed)
}

/**
 * CancellationPolicy은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object CancellationPolicy {
    /**
     * 주문·fulfillment 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    private val allowed =
        mapOf(
            CancellationStatus.REQUESTED to setOf(CancellationStatus.APPROVED, CancellationStatus.REJECTED)
        )

    /**
     * transition은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        from: CancellationStatus,
        to: CancellationStatus,
    ) = transition("cancellation", from, to, allowed)
}

/**
 * RefundPolicy은 주문 이행 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal object RefundPolicy {
    /**
     * 주문·fulfillment 정책을 선택하는 플래그입니다. true/false별 처리 경로와 기본값을 계약으로 유지합니다.
     */
    private val allowed =
        mapOf(
            RefundStatus.REQUESTED to setOf(RefundStatus.PENDING_PROVIDER, RefundStatus.MANUAL_REVIEW),
            RefundStatus.PENDING_PROVIDER to setOf(
                RefundStatus.SUCCEEDED,
                RefundStatus.FAILED,
                RefundStatus.MANUAL_REVIEW
            ),
            RefundStatus.FAILED to setOf(RefundStatus.PENDING_PROVIDER, RefundStatus.MANUAL_REVIEW)
        )

    /**
     * transition은 주문 라이프사이클과 fulfillment의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param from 업무 처리에 필요한 from 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param to 업무 처리에 필요한 to 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun transition(
        from: RefundStatus,
        to: RefundStatus,
    ) = transition("refund", from, to, allowed)
}
