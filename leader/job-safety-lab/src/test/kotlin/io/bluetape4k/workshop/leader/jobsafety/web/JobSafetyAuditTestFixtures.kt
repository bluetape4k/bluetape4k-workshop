package io.bluetape4k.workshop.leader.jobsafety.web

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.workshop.leader.jobsafety.audit.JobSafetyAuditReport
import io.bluetape4k.workshop.leader.jobsafety.audit.JobSafetyAuditReportPort
import io.bluetape4k.workshop.leader.jobsafety.audit.JobSafetyAuditSnapshot

internal fun testAuditReportPort(): JobSafetyAuditReportPort = JobSafetyAuditReportPort {
    JobSafetyAuditReport(
        transport = "MEMORY",
        enabled = true,
        recentEvents = listOf(Jackson.defaultJsonMapper.readTree("{\"status\":\"COMPLETED\"}")),
        retainedPayloadCount = 1,
        retainedPayloadBytes = 31,
        snapshot = JobSafetyAuditSnapshot(
            queued = 0,
            inFlight = 0,
            scheduledRetries = 0,
            admitted = 0,
            accepted = 1,
            droppedQueueFull = 0,
            droppedClosed = 0,
            retries = 0,
            terminalFailures = 0,
            cancellations = 0,
            executorRejections = 0,
            schedulerRejections = 0,
            observerDrops = 0,
            observerRegistrationDrops = 0,
            diagnosticsFatalErrors = 0,
            diagnosticsClosed = false,
            closed = false,
        ),
        meters = listOf("leader.audit.export.accepted", "leader.audit.export.dropped"),
    )
}
