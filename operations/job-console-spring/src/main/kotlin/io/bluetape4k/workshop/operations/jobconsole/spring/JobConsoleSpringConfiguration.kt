package io.bluetape4k.workshop.operations.jobconsole.spring

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
import java.util.concurrent.Executors
import javax.sql.DataSource

@Configuration
class JobConsoleSpringConfiguration {
    @Bean
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

    @Bean
    fun jobEventFanout(): BoundedJobEventFanout = BoundedJobEventFanout(Duration.ofSeconds(2))

    @Bean
    fun jobConsoleService(
        repository: JobRepository,
        signalProvider: ObjectProvider<CancelSignal>,
        @Value("\${job-console.bounded-wait.enabled:false}") boundedWaitEnabled: Boolean,
        @Value("\${job-console.bounded-wait.policy-fingerprint:}") expectedPolicyFingerprint: String,
    ): JobConsoleService =
        JobConsoleService(
            repository = repository,
            cancelSignal = signalProvider.ifAvailable ?: io.bluetape4k.workshop.operations.jobconsole.signal.NoOpCancelSignal,
            boundedWaitEnabled = boundedWaitEnabled,
            expectedPolicyFingerprint = expectedPolicyFingerprint.takeIf(String::isNotBlank),
        )

    @Bean
    fun jobCancelSignal(@Value("\${job-console.redis-uri:}") redisUri: String): CancelSignal =
        redisUri.takeIf(String::isNotBlank)?.let { runCatching { LettuceCancelSignal(it) }.getOrNull() }
            ?: NoOpCancelSignal

    @Bean
    fun jobWorkerEngine(repository: JobRepository): JobWorkerEngine =
        JobWorkerEngine(repository, DeterministicJobWorkload())

    @Bean(destroyMethod = "close")
    fun jobWorkerExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    @Bean
    fun jobOutboxPoller(dataSource: DataSource, fanout: BoundedJobEventFanout): JobOutboxPoller =
        JobOutboxPoller(JobOutboxRepository(dataSource), fanout)

    @Bean
    fun jobConsoleSpringLifecycle(service: JobConsoleService): JobConsoleSpringLifecycle =
        JobConsoleSpringLifecycle(service)
}
