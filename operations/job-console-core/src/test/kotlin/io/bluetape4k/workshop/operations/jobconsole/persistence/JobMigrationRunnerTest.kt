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

            fixture.count("job_schema_history") shouldBeEqualTo 1L
            fixture.count("jobs") shouldBeEqualTo 0L
            fixture.count("job_requests") shouldBeEqualTo 0L
            fixture.count("job_outbox") shouldBeEqualTo 0L
        }
    }

    @Test
    fun `checksum drift fails closed`() {
        JobConsoleDatabaseFixture().use { fixture ->
            val original = JobMigration("probe", "CREATE TABLE migration_probe(id BIGINT PRIMARY KEY)".toByteArray())
            val drifted = JobMigration("probe", "CREATE TABLE migration_probe(id UUID PRIMARY KEY)".toByteArray())

            JobMigrationRunner(fixture.dataSource, listOf(original), 520002L).migrate()
            val failure =
                assertFailsWith<JobMigrationException> {
                    JobMigrationRunner(fixture.dataSource, listOf(drifted), 520002L).migrate()
                }

            failure.code shouldBeEqualTo JobMigrationFailureCode.CHECKSUM_DRIFT
            fixture.count("job_schema_history") shouldBeEqualTo 1L
        }
    }
}
