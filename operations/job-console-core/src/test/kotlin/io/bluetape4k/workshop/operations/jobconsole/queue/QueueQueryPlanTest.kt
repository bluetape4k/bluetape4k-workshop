package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.SubmitJobRequest
import io.bluetape4k.workshop.operations.jobconsole.application.JobConsoleService
import io.bluetape4k.workshop.operations.jobconsole.persistence.DemoCallerScope
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobConsoleDatabaseFixture
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobRepository
import io.bluetape4k.workshop.operations.jobconsole.persistence.JobTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant

@Tag("integration")
/**
 * QueueQueryPlanTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class QueueQueryPlanTest {

    companion object: KLogging() {
/**
 * NOW은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }

    @Test
/**
 * 이 시나리오는 "queue reads are tenant scoped and samples are bounded" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `queue reads are tenant scoped and samples are bounded`() {
        JobConsoleDatabaseFixture().use { fixture ->
            fixture.migrate()
            val repository = JobRepository(fixture.dataSource)
            val scope = DemoCallerScope("tenant-a", "submitter-a")
            val submitted = (0 until 120).map { index ->
                repository.submit(scope, "key-$index", SubmitJobRequest(JobType.DOCUMENT_EXPORT, 1), NOW)
            }

            repository.queueRows("tenant-a", afterSequence = null, limit = 1_000).size shouldBeEqualTo 100
            repository.queueRows("tenant-b", afterSequence = null, limit = 10).size shouldBeEqualTo 0

            val service = JobConsoleService(repository)
            service.snapshot(scope, submitted.last().jobId).queue?.position shouldBeEqualTo 120

            val firstPage = service.tenantQueue(scope.tenantId, null, 100)
            firstPage.items.size shouldBeEqualTo 100

            val secondPage = service.tenantQueue(scope.tenantId, firstPage.nextCursor, 100)
            secondPage.items.size shouldBeEqualTo 20
            secondPage.nextCursor.shouldBeNull()

            repeat(120) { index ->
                repository.recordDuration(JobType.DOCUMENT_EXPORT, (index + 1).seconds(), NOW)
            }
            repository.recordDuration(JobType.REPORT_GENERATION, 999.seconds(), NOW)
            repository.durationSamples(
                JobType.DOCUMENT_EXPORT,
                NOW - 1.seconds(),
                limit = 1_000
            ).size shouldBeEqualTo 100

            fixture.execute("ANALYZE jobs")
            val plan = fixture.explainPlan {
                JobTable
                    .select(JobTable.jobId)
                    .where { (JobTable.tenantId eq "tenant-a") and (JobTable.state eq "queued") }
                    .orderBy(JobTable.enqueueSequence to SortOrder.ASC)
                    .limit(25)
            }.joinToString("\n")

            log.debug { "plan=$plan" }
            (plan.contains("ix_job_tenant_queue") || plan.contains("Index Scan")).shouldBeTrue()
        }
    }
}
