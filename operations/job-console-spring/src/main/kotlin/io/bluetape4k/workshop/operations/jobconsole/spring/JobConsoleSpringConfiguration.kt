package io.bluetape4k.workshop.operations.jobconsole.spring

import io.bluetape4k.workshop.operations.jobconsole.application.BoundedJobEventFanout
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.application.JobOutboxPoller
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigration
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobMigrationRunner
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobOutboxRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import javax.sql.DataSource

@Configuration
class JobConsoleSpringConfiguration {
    @Bean
    fun jobRepository(dataSource: DataSource): JobRepository {
        JobMigrationRunner(
            dataSource,
            listOf(JobMigration.classpath("001", "db/job-console/V001__job_console.sql")),
            advisoryLockKey = 520_001L,
        ).migrate()
        return JobRepository(dataSource)
    }

    @Bean
    fun jobEventFanout(): BoundedJobEventFanout = BoundedJobEventFanout(Duration.ofSeconds(2))

    @Bean
    fun jobConsoleService(repository: JobRepository): JobConsoleService = JobConsoleService(repository)

    @Bean
    fun jobOutboxPoller(dataSource: DataSource, fanout: BoundedJobEventFanout): JobOutboxPoller =
        JobOutboxPoller(JobOutboxRepository(dataSource), fanout)
}
