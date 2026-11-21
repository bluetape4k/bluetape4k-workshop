package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import jakarta.annotation.PreDestroy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the admission side of Spring shutdown. Spring stops scheduled beans and
 * container-managed executors around this callback; the service gate closes
 * first so a late request cannot create a new owner while resources drain.
 * @property service 운영 작업 콘솔 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
class JobConsoleSpringLifecycle(
    /**
     * `service`은 작업 운영 콘솔에서 업무 규칙을 위임하는 저장소·외부 시스템 협력자입니다.
     *
     * 기준 데이터와 재시도·timeout 정책을 숨기지 않고 호출자가 결과를 복구할 수 있게 합니다.
     */
    private val service: JobConsoleService,
) {

    companion object: KLogging()
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val stopped = AtomicBoolean()

    @PreDestroy
    /**
     * stop은 운영 작업 콘솔 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        service.closeAdmission()
        service.awaitSubmissionQuiescence(5.seconds())
    }
}
