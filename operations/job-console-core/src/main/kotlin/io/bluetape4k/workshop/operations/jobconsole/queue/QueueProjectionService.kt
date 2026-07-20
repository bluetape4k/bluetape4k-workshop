package io.bluetape4k.workshop.operations.jobconsole.queue

import io.bluetape4k.workshop.operations.jobconsole.api.EtaConfidence
import io.bluetape4k.workshop.operations.jobconsole.api.JobType
import io.bluetape4k.workshop.operations.jobconsole.api.QueueProjection
import io.bluetape4k.workshop.operations.jobconsole.domain.JobState
import java.nio.ByteBuffer
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class QueueRow(
    val jobId: UUID,
    val jobType: JobType,
    val state: JobState,
    val enqueueSequence: Long,
    val queueVersion: Long,
    val updatedAt: Instant,
)

data class QueuePage(
    val items: List<QueueRow>,
    val nextCursor: String?,
)

object QueueProjectionService {
    const val DEFAULT_PAGE_SIZE: Int = 25
    const val MAX_PAGE_SIZE: Int = 100

    fun project(rows: List<QueueRow>, targetJobId: UUID, now: Instant): QueueProjection {
        val active = rows.filterNot { it.state.terminal }.sortedBy { it.enqueueSequence }
        val targetIndex = active.indexOfFirst { it.jobId == targetJobId }
        require(targetIndex >= 0) { "target job is not in the active tenant queue" }
        val target = active[targetIndex]
        return QueueProjection(
            position = targetIndex + 1,
            jobsAhead = targetIndex,
            estimatedStartRange = null,
            estimatedCompletionRange = null,
            confidence = EtaConfidence.INSUFFICIENT_DATA,
            sampleSize = 0,
            queueVersion = target.queueVersion,
            updatedAt = now,
        )
    }

    fun page(rows: List<QueueRow>, cursor: String?, requestedSize: Int): QueuePage {
        val size = requestedSize.coerceIn(1, MAX_PAGE_SIZE)
        val after = cursor?.let(::decodeCursor)
        val candidates = rows.asSequence().filter { after == null || it.enqueueSequence > after }.sortedBy { it.enqueueSequence }.toList()
        val items = candidates.take(size)
        val next = if (candidates.size > size) encodeCursor(items.last().enqueueSequence) else null
        return QueuePage(items, next)
    }

    private fun encodeCursor(sequence: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(sequence).array())

    private fun decodeCursor(cursor: String): Long =
        runCatching { ByteBuffer.wrap(Base64.getUrlDecoder().decode(cursor)).long }
            .getOrElse { throw IllegalArgumentException("invalid queue cursor") }
}
