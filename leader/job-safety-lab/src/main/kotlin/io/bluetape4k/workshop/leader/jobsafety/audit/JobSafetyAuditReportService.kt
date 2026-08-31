package io.bluetape4k.workshop.leader.jobsafety.audit

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.leader.audit.LeaderAuditExportSnapshot
import io.bluetape4k.leader.audit.LeaderAuditExporter
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode

/**
 * job-safety audit의 일시적인 관찰 상태를 operator report로 투영합니다.
 *
 * 이 report는 PostgreSQL history나 외부 audit system의 authoritative 상태가 아닙니다.
 * retained payload를 요청 시점에 JSON tree로 decode하고, exporter snapshot과 upstream
 * public meter 이름만 반환합니다. endpoint, authorization, raw payload 문자열은 report
 * 모델에 존재하지 않습니다.
 */
class JobSafetyAuditReportService(
    private val transport: String,
    private val enabled: Boolean,
    private val payloadStore: BoundedAuditPayloadStore,
    private val exporter: LeaderAuditExporter,
    private val meterNames: List<String> = JobSafetyAuditMeterCatalog.names,
) : JobSafetyAuditReportPort {

    private val logger = LoggerFactory.getLogger(JobSafetyAuditReportService::class.java)

    override fun report(): JobSafetyAuditReport {
        val payloads = payloadStore.snapshot()
        var malformedPayloadCount = 0
        val recentEvents = buildList {
            payloads.forEachIndexed { index, payload ->
                decodeSafely(payload, index)?.let(::add) ?: run { malformedPayloadCount++ }
            }
        }
        return JobSafetyAuditReport(
            transport = transport,
            enabled = enabled,
            recentEvents = recentEvents,
            retainedPayloadCount = payloads.size,
            retainedPayloadBytes = payloads.sumOf { it.size.toLong() },
            malformedPayloadCount = malformedPayloadCount,
            snapshot = JobSafetyAuditSnapshot.from(exporter.snapshot()),
            meters = meterNames.toList(),
        )
    }

    private fun decodeSafely(payload: ByteArray, index: Int): JsonNode? = try {
        Jackson.defaultJsonMapper.readTree(payload)
    } catch (error: Exception) {
        logger.warn(
            "job_safety_audit_report_decode_failed payload_index={} payload_bytes={} error_type={}",
            index,
            payload.size,
            error::class.qualifiedName ?: "unknown",
        )
        null
    }
}

/** report endpoint가 의존하는 최소 관찰 port입니다. */
fun interface JobSafetyAuditReportPort {
    fun report(): JobSafetyAuditReport
}

/** operator report의 transport 및 bounded event view입니다. */
data class JobSafetyAuditReport(
    val transport: String,
    val enabled: Boolean,
    val recentEvents: List<JsonNode>,
    val retainedPayloadCount: Int,
    val retainedPayloadBytes: Long,
    val malformedPayloadCount: Int,
    val snapshot: JobSafetyAuditSnapshot,
    val meters: List<String>,
)

/** upstream exporter snapshot의 JSON-safe copy입니다. */
data class JobSafetyAuditSnapshot(
    val queued: Int,
    val inFlight: Int,
    val scheduledRetries: Int,
    val admitted: Int,
    val accepted: Long,
    val droppedQueueFull: Long,
    val droppedClosed: Long,
    val retries: Long,
    val terminalFailures: Long,
    val cancellations: Long,
    val executorRejections: Long,
    val schedulerRejections: Long,
    val observerDrops: Long,
    val observerRegistrationDrops: Long,
    val diagnosticsFatalErrors: Long,
    val diagnosticsClosed: Boolean,
    val closed: Boolean,
) {
    companion object {
        fun from(snapshot: LeaderAuditExportSnapshot): JobSafetyAuditSnapshot = JobSafetyAuditSnapshot(
            queued = snapshot.queued,
            inFlight = snapshot.inFlight,
            scheduledRetries = snapshot.scheduledRetries,
            admitted = snapshot.admitted,
            accepted = snapshot.accepted,
            droppedQueueFull = snapshot.droppedQueueFull,
            droppedClosed = snapshot.droppedClosed,
            retries = snapshot.retries,
            terminalFailures = snapshot.terminalFailures,
            cancellations = snapshot.cancellations,
            executorRejections = snapshot.executorRejections,
            schedulerRejections = snapshot.schedulerRejections,
            observerDrops = snapshot.observerDrops,
            observerRegistrationDrops = snapshot.observerRegistrationDrops,
            diagnosticsFatalErrors = snapshot.diagnosticsFatalErrors,
            diagnosticsClosed = snapshot.diagnosticsClosed,
            closed = snapshot.closed,
        )
    }
}

/** upstream internal MicrometerNames를 consumer 경계에서 재노출하지 않는 고정 catalog입니다. */
object JobSafetyAuditMeterCatalog {
    val names: List<String> = listOf(
        "leader.audit.export.accepted",
        "leader.audit.export.cancelled",
        "leader.audit.export.diagnostics.closed",
        "leader.audit.export.diagnostics.failures",
        "leader.audit.export.dropped",
        "leader.audit.export.failures",
        "leader.audit.export.in.flight",
        "leader.audit.export.observer.dropped",
        "leader.audit.export.observer.registration.dropped",
        "leader.audit.export.queue.depth",
        "leader.audit.export.rejections",
        "leader.audit.export.retries",
    )
}
