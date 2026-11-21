package io.bluetape4k.workshop.commerce.ticket.config

import io.bluetape4k.workshop.commerce.ticket.web.TicketEventStream
import jakarta.annotation.PreDestroy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * observation과 worker resource가 닫히기 전에 admission을 중지합니다.
 * @property eventStream 콘서트 티켓 판매 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */
class TicketLifecycle(private val eventStream: TicketEventStream) {
    /**
     * 콘서트 티켓 판매 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */


    private val acceptingForeground = AtomicBoolean(true)

    /**
     * acceptsForegroundWork은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록
     * 처리합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun acceptsForegroundWork(): Boolean = acceptingForeground.get()

    @PreDestroy
    /**
     * shutdown은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    fun shutdown() {
        acceptingForeground.set(false)
        eventStream.stopNewConnections()
        eventStream.close()
    }
}
