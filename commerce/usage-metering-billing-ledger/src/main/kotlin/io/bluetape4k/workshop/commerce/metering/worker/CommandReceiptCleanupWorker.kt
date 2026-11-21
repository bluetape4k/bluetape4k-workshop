package io.bluetape4k.workshop.commerce.metering.worker

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
/**
 * CommandReceiptCleanupWorker은 사용량 계량·청구에서 사용하는 업무 객체입니다.
 * @property commandReceiptService 사용량 계량·청구 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */


@Component
class CommandReceiptCleanupWorker(
    /**
     * `commandReceiptService`은 사용량 과금·정산에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val commandReceiptService: CommandReceiptService,
) {

    companion object: KLogging()

    /**
     * cleanup은 사용량 계량·청구의 업무 규칙을 한 곳에서 적용하여 검증·상태 전이·영속화 결과가 서로 어긋나지 않도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Scheduled(fixedDelayString = "PT5M")
    fun cleanup() {
        commandReceiptService.cleanupExpiredTerminal()
    }
}
