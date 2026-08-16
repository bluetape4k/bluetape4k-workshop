package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class JobMigrationRunnerTest {

    @Test
    fun `production migration creates the complete authority schema and replays`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate() shouldBeEqualTo JobMigrationResult.APPLIED
            fixture.migrate() shouldBeEqualTo JobMigrationResult.ALREADY_APPLIED

            fixture.count("job_schema_history") shouldBeEqualTo 2L
            fixture.count("jobs") shouldBeEqualTo 0L
            fixture.count("job_requests") shouldBeEqualTo 0L
            fixture.count("job_outbox") shouldBeEqualTo 0L
        }
    }

    @Test
    fun `checksum drift fails closed`() {
        JobConsoleDatabaseFixture().use { fixture ->
            val original = JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql")
            val drifted = JobMigration("002", "CREATE TABLE migration_probe(id UUID PRIMARY KEY)".toByteArray())

            JobMigrationRunner(
                fixture.dataSource,
                listOf(JobMigration.classpath("001", "db/job-console/V001__job_console.sql"), original),
                520002L,
            ).migrate()
            fixture.count("job_schema_history") shouldBeEqualTo 2L
            val failure =
                assertFailsWith<JobMigrationException> {
                    JobMigrationRunner(
                        fixture.dataSource,
                        listOf(JobMigration.classpath("001", "db/job-console/V001__job_console.sql"), drifted),
                        520002L,
                    ).migrate()
                }

            failure.code shouldBeEqualTo JobMigrationFailureCode.CHECKSUM_DRIFT
            fixture.count("job_schema_history") shouldBeEqualTo 2L
        }
    }
}
