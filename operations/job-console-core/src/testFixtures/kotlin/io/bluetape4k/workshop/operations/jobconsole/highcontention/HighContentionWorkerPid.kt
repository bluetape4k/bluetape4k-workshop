package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.junit5.process.WorkerPidPublisher

/** operations 고경합 실행기의 기존 호출 경계를 유지하는 thin wrapper입니다. */
object HighContentionWorkerPid {

    fun publishIfConfigured(): Unit = WorkerPidPublisher.publishIfConfigured(
        pidFileProperty = "highContentionWorkerPidFile",
        processOwnerProperty = "highContentionProcessOwner",
    )
}
