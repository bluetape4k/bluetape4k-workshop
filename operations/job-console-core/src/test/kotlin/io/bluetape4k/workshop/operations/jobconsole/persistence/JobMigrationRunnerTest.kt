package io.bluetape4k.workshop.operations.jobconsole.persistence

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** startup migration이 authority schema를 만들고 checksum drift에서 fail closed하는지 검증한다. */
/** startup migration이 authority schema를 만들고 checksum drift에서 fail closed하는지 검증한다. */
@Tag("integration")
class JobMigrationRunnerTest {

    @Test
/**
 * 이 시나리오는 "production migration creates the complete authority schema and replays" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "checksum drift fails closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `checksum drift fails closed`() {
        JobConsoleDatabaseFixture().use { fixture ->
            val original = JobMigration.classpath("002", "db/job-console/V002__bounded_wait_http_idempotency.sql")
            val drifted = JobMigration("002", "CREATE TABLE migration_probe(id UUID PRIMARY KEY)".toUtf8Bytes())

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
