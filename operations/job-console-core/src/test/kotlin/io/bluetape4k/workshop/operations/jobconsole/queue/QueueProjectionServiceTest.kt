package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class QueueProjectionServiceTest {

    companion object: KLogging() {
        private val NOW = Instant.parse("2026-07-21T00:00:00Z")
    }

    @Test
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
    fun `cursor is opaque and page size is capped`() {
        val page = QueueProjectionService.page(
            rows = (1..105).map { row(it.toLong(), JobState.QUEUED) },
            cursor = null,
            requestedSize = 500
        )

        page.items.size shouldBeEqualTo QueueProjectionService.MAX_PAGE_SIZE
        page.nextCursor shouldNotContain "tenant"
    }

    private fun row(sequence: Long, state: JobState) =
        QueueRow(
            jobId = UUID.nameUUIDFromBytes("job-$sequence".toByteArray()),
            jobType = JobType.DOCUMENT_EXPORT,
            state = state,
            enqueueSequence = sequence,
            queueVersion = sequence,
            updatedAt = NOW,
        )

}
