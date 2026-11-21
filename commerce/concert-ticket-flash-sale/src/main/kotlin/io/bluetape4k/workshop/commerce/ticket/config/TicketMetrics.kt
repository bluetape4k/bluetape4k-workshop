package io.bluetape4k.workshop.commerce.ticket.config

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap

/**
 * low-cardinality metrics facade입니다. sale, buyer, IP, operation, attempt ID는 tag로 금지합니다.
 * @property registry 콘서트 티켓 판매 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class TicketMetrics(private val registry: MeterRegistry) {
    /**
     * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
     */

    private val outcomes = ConcurrentHashMap<String, Counter>()

    /**
     * recordOutcome은 콘서트 티켓 판매 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param outcome 허용된 업무 상태 또는 처리 결과입니다. 임의의 문자열을 다음 전이 기준으로 사용하지 않습니다.
     */
    fun recordOutcome(outcome: String) {
        val outcomeValue = outcome.lowercase()
        require(outcomeValue in ALLOWED_OUTCOMES) { "Outcome[$outcomeValue] is not allowed" }

        outcomes.computeIfAbsent(outcomeValue) {
            Counter.builder("ticket.purchase.outcomes")
                .tags(Tags.of("outcome", outcomeValue))
                .register(registry)
        }.increment()
    }

    companion object {
        /**
         * 콘서트 티켓 판매의 상태·분류·오류 코드를 나타냅니다. 허용된 전이와 재시도·응답 분기를 선택하는 기준입니다.
         */
        private val ALLOWED_OUTCOMES = setOf("approved", "declined", "cancelled", "refunded", "quarantined")
    }
}
