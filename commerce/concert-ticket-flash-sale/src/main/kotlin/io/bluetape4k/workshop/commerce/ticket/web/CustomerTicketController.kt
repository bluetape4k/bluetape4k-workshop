package io.bluetape4k.workshop.commerce.ticket.web

import io.bluetape4k.workshop.commerce.ticket.purchase.api.CancelPurchase
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseCommands
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseQueries
import io.bluetape4k.workshop.commerce.ticket.purchase.api.PurchaseSnapshot
import io.bluetape4k.workshop.commerce.ticket.purchase.internal.PurchaseNotFound
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

/**
 * owner-safe purchase recovery endpoint입니다. 다른 owner의 ID는 없는 ID와 동일하게 404를 반환합니다.
 * @property commands 콘서트 티켓 판매 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 * @property queries 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property buyers 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
@RestController
@RequestMapping("/api/v1/purchase-attempts")
class CustomerTicketController(
    /**
     * `commands`은 콘서트 티켓 플래시 세일에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commands: PurchaseCommands,
    /**
     * `queries`은 콘서트 티켓 플래시 세일에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val queries: PurchaseQueries,
    /**
     * `buyers`은 콘서트 티켓 플래시 세일에서 권한과 데이터 범위를 판정하는 호출 주체 또는 소유자 정보입니다.
     *
     * 인증·인가 경계를 통과한 값만 사용하고 다른 tenant의 상태·payload가 섞이지 않도록 합니다.
     */
    private val buyers: AuthenticatedBuyerResolver,
) {
    /**
     * `get`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `GetMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = get(attemptId = Uuid.V7.nextId(), authentication = authentication)
     * ```
     * @param attemptId 요청 경계에서 검증된 `attemptId`입니다.
     * @param authentication 요청 경계에서 검증된 `authentication`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @GetMapping("/{attemptId}")
    fun get(@PathVariable attemptId: UUID, authentication: Authentication): ResponseEntity<PurchaseSnapshot> =
        ownerSnapshot(attemptId, authentication).noStore()

    /**
     * `cancel`은 HTTP API의 업무 진입점입니다.
     *
     * 입력은 인증 주체·tenant scope·idempotency key와 함께 검증하고, command라면 동일 revision에서 허용된 상태 전이만 수행합니다. 반환
     * snapshot은 commit된 source-of-truth로 취급하며 SSE/event 알림만으로 현재 상태를 추정하지 않습니다.
     * 매핑 경계: `PostMapping`. 인증 실패·검증 실패·멱등성 충돌은 성공 응답처럼 숨기지 않고 계약된 오류로 반환합니다.
     * 예:
     * ```kotlin
     * val response = cancel(attemptId = Uuid.V7.nextId(), authentication = authentication)
     * ```
     * @param attemptId 요청 경계에서 검증된 `attemptId`입니다.
     * @param authentication 요청 경계에서 검증된 `authentication`입니다.
     * @return 성공 시 확정된 결과를, 실패 시 호출자가 분기할 수 있는 오류 계약을 반환합니다.
     */
    @PostMapping("/{attemptId}/cancellation")
    fun cancel(@PathVariable attemptId: UUID, authentication: Authentication): ResponseEntity<PurchaseSnapshot> {
        val buyerId = buyers.resolve(authentication)
        if (queries.owned(attemptId, buyerId) == null) throw PurchaseNotFound()

        return ResponseEntity.accepted()
            .cacheControl(CacheControl.noStore())
            .body(commands.cancel(CancelPurchase(attemptId, buyerId)))
    }

    /**
     * ownerSnapshot은 콘서트 티켓 판매의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param attemptId 처리할 콘서트 티켓 판매 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param authentication 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    private fun ownerSnapshot(attemptId: UUID, authentication: Authentication): PurchaseSnapshot =
        queries.owned(attemptId, buyers.resolve(authentication)) ?: throw PurchaseNotFound()

    /**
     * noStore은 콘서트 티켓 판매의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    private fun PurchaseSnapshot.noStore(): ResponseEntity<PurchaseSnapshot> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(this)
}
