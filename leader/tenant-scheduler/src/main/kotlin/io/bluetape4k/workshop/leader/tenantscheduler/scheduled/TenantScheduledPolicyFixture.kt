package io.bluetape4k.workshop.leader.tenantscheduler.scheduled

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.atomic.AtomicInteger

/**
 * YAML scheduled policy가 main source-set의 plain `@Scheduled` method에 적용되는 fixture입니다.
 *
 * 첫 자동 실행은 최대 60초 뒤에 시작합니다. 동기 호출 예제에서는 local factory의
 * `min-lease-time` floor가 실행 완료 뒤에도 잠시 기다리게 할 수 있습니다. local factory는
 * 설정과 Spring runtime proxy를 학습하기 위한 단일 프로세스 경로이며 distributed ownership을
 * 증명하지 않습니다. upstream artifact의 external CTW singleton 경로는 이 consumer
 * 예제에서 사용하지 않고, Spring proxy가 관리하는 `open` fixture로 runtime 경계를
 * 명시합니다. callback마다 bounded invocation-count 로그를 남겨 `bootRun`에서도
 * 실제 trigger를 확인할 수 있습니다.
 */
open class TenantScheduledPolicyFixture {

    companion object : KLogging()

    private val invocations = AtomicInteger()

    /** tenant reconciliation callback을 실행합니다. */
    @Scheduled(fixedDelay = 5_000, initialDelay = 60_000)
    open fun reconcile() {
        val count = invocations.incrementAndGet()
        log.info { "tenant-scheduler callback completed invocationCount=$count" }
    }

    /** 테스트가 callback 실행 횟수를 관찰할 수 있도록 현재 값을 반환합니다. */
    open fun invocationCount(): Int = invocations.get()
}
