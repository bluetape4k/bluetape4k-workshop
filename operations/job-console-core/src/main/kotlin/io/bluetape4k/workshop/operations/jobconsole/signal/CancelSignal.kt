package io.bluetape4k.workshop.operations.jobconsole.signal

import java.util.UUID

data class CancelSignalResult(
    val delivered: Boolean,
)

fun interface CancelSignal {
    fun publish(jobId: UUID): CancelSignalResult

    fun isAvailable(): Boolean = true
}

object NoOpCancelSignal : CancelSignal {
    override fun publish(jobId: UUID): CancelSignalResult = CancelSignalResult(delivered = false)

    override fun isAvailable(): Boolean = false
}
