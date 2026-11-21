package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.concurrent.virtualthread.api.VirtualThreads
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.operations.jobconsole.application.BoundedJobEventFanout
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.application.JobOutboxPoller
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigration
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigrationRunner
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.signal.CancelSignal
import io.bluetape4k.workshop.operations.jobconsole.signal.LettuceCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.signal.NoOpCancelSignal
import io.bluetape4k.workshop.operations.jobconsole.worker.DeterministicJobWorkload
import io.bluetape4k.workshop.operations.jobconsole.worker.JobWorkerEngine
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import java.time.Duration
import java.util.concurrent.ExecutorService
import javax.sql.DataSource

@Configuration
/**
 * Wires the framework-neutral core into Spring-managed lifecycle components.
 *
 * The configuration runs the ordered example migrations once at startup,
 * creates the application-owned virtual-thread executor, and chooses the
 * advisory Redis cancel signal when a URI is configured. PostgreSQL repository
 * state remains authoritative when Redis is absent.
 */
class JobConsoleSpringConfiguration {

    companion object: KLogging()

    /**
     * jobRepository은 운영 작업 콘솔 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    @Bean(destroyMethod = "close")
    fun jobRepository(dataSource: DataSource): JobRepository {
        JobMigrationRunner(
            dataSource,
            listOf(
                JobMigration.classpath("001", "db/job-console/V001__job_console.sql"),
                JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql"),
            ),
            advisoryLockKey = 520_001L,
        ).migrate()
        return JobRepository(dataSource)
    }

    /**
     * jobEventFanout은 운영 작업 콘솔 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param jobWorkerExecutor 업무 처리에 필요한 jobWorkerExecutor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun jobEventFanout(jobWorkerExecutor: ExecutorService): BoundedJobEventFanout =
        BoundedJobEventFanout(2.seconds(), jobWorkerExecutor)

    /**
     * jobConsoleService은 운영 작업 콘솔 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param signalProvider 업무 처리에 필요한 signalProvider 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param jobWorkerExecutor 업무 처리에 필요한 jobWorkerExecutor 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param boundedWaitEnabled 업무 처리에 필요한 boundedWaitEnabled 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param expectedPolicyFingerprint 낙관적 동시성 또는 이벤트 순서를 확인하는 기대값입니다. 불일치하면 오래된 명령으로 처리합니다.
     */
    @Bean(destroyMethod = "close")
    fun jobConsoleService(
        repository: JobRepository,
        signalProvider: ObjectProvider<CancelSignal>,
        jobWorkerExecutor: ExecutorService,
        @Value("\${job-console.bounded-wait.enabled:false}") boundedWaitEnabled: Boolean,
        @Value("\${job-console.bounded-wait.policy-fingerprint:}") expectedPolicyFingerprint: String,
    ): JobConsoleService =
        JobConsoleService(
            repository = repository,
            cancelSignal = signalProvider.ifAvailable ?: NoOpCancelSignal,
            boundedWaitEnabled = boundedWaitEnabled,
            expectedPolicyFingerprint = expectedPolicyFingerprint.takeIf(String::isNotBlank),
            executor = jobWorkerExecutor,
        )

    /**
     * jobCancelSignal은 운영 작업 콘솔의 취소·보상·종료 전이를 허용된 terminal 규칙에 따라 적용하고, 이미 처리된 요청은 멱등하게 유지합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param redisUri 운영 작업 콘솔 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
    @Bean
    fun jobCancelSignal(@Value("\${job-console.redis-uri:}") redisUri: String): CancelSignal =
        redisUri.takeIf(String::isNotBlank)?.let { runCatching { LettuceCancelSignal(it) }.getOrNull() }
            ?: NoOpCancelSignal

    /**
     * jobWorkerEngine은 운영 작업 콘솔 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param repository 업무 처리에 필요한 repository 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun jobWorkerEngine(repository: JobRepository): JobWorkerEngine =
        JobWorkerEngine(repository, DeterministicJobWorkload())

    /**
     * jobWorkerExecutor은 운영 작업 콘솔 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean(destroyMethod = "close")
    fun jobWorkerExecutor(): ExecutorService = VirtualThreads.executorService()

    /**
     * jobOutboxPoller은 운영 작업 콘솔 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와 재전송
     * 결과를 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param dataSource 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param fanout 업무 처리에 필요한 fanout 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean(destroyMethod = "close")
    fun jobOutboxPoller(dataSource: DataSource, fanout: BoundedJobEventFanout): JobOutboxPoller =
        JobOutboxPoller(JobOutboxRepository(dataSource), fanout)

    /**
     * jobConsoleSpringLifecycle은 운영 작업 콘솔 실행에 필요한 구성과 운영 수치를 제공하며, 잘못된 값이 실제 업무 처리 전에 드러나도록 합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param service 업무 처리에 필요한 service 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun jobConsoleSpringLifecycle(service: JobConsoleService): JobConsoleSpringLifecycle =
        JobConsoleSpringLifecycle(service)
}
