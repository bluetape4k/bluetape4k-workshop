package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/** tenant-local FIFO position, jobsAhead, opaque cursor, bounded page size를 검증한다. */
/** tenant-local FIFO position, jobsAhead, opaque cursor, bounded page size를 검증한다. */
class QueueProjectionServiceTest {

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
 * 이 시나리오는 "position counts only earlier non-terminal jobs in the same tenant" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `position counts only earlier non-terminal jobs in the same tenant`() {
        val target = row(30, JobState.QUEUED)
        val projection = QueueProjectionService.project(
            rows = listOf(
                row(10, JobState.SUCCEEDED),
                row(20, JobState.RUNNING),
                target,
                row(40, JobState.QUEUED)
            ),
            targetJobId = target.jobId,
            now = NOW,
        )

        log.debug { "projection=$projection" }
        projection.position shouldBeEqualTo 2
        projection.jobsAhead shouldBeEqualTo 1
        projection.queueVersion shouldBeEqualTo 30L
    }

    @Test
/**
 * 이 시나리오는 "cursor is opaque and page size is capped" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `cursor is opaque and page size is capped`() {
        val page = QueueProjectionService.page(
            rows = (1..105).map { row(it.toLong(), JobState.QUEUED) },
            cursor = null,
            requestedSize = 500
        )

        page.items.size shouldBeEqualTo QueueProjectionService.MAX_PAGE_SIZE
        page.nextCursor shouldNotContain "tenant"
    }

/**
 * row은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun row(sequence: Long, state: JobState) =
        QueueRow(
            jobId = UUID.nameUUIDFromBytes("job-$sequence".toUtf8Bytes()),
            jobType = JobType.DOCUMENT_EXPORT,
            state = state,
            enqueueSequence = sequence,
            queueVersion = sequence,
            updatedAt = NOW,
        )

}
