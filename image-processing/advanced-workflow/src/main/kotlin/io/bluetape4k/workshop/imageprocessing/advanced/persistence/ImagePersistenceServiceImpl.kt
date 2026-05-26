package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.workshop.imageprocessing.advanced.model.AssetMetadataInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDetailResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetHistoryResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetNotFoundException
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageJobWithEventsDTO
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectKind
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobFailureReason
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository.ImageAssetRepository
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository.ImageObjectRepository
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository.ImageProcessingEventRepository
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.repository.ImageProcessingJobRepository
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageAssetTable
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Programmatic-transaction implementation of [ImagePersistenceService].
 *
 * ## Transaction strategy
 * Each write method runs inside its own `REQUIRES_NEW` transaction via [txTemplate].
 * No `@Transactional` annotation is placed on any method — all transaction
 * boundaries are managed explicitly.
 *
 * ## Audit user
 * Every write method wraps its entire body in [UserContext.withThreadLocalUser] with [AUDIT_USER],
 * so audit columns (`created_by`, `updated_by`) are populated correctly.
 * [UserContext.withThreadLocalUser] is used instead of [UserContext.withUser] because
 * this service is called from coroutine dispatchers (Dispatchers.IO), where ScopedValue
 * (used internally by withUser) is not compatible.
 *
 * ## Concurrency
 * [recordJobStart] catches [DataIntegrityViolationException] (DIVE) outside the
 * [txTemplate] block — after the failed transaction has been rolled back — then
 * re-reads the conflicting row and branches accordingly.
 */
@Service
class ImagePersistenceServiceImpl(
    transactionManager: PlatformTransactionManager,
    private val assetRepo: ImageAssetRepository,
    private val objectRepo: ImageObjectRepository,
    private val jobRepo: ImageProcessingJobRepository,
    private val eventRepo: ImageProcessingEventRepository,
) : ImagePersistenceService {

    companion object : KLogging() {
        private const val AUDIT_USER = "image-processing-service"
    }

    private val txTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    private val readTxTemplate = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        // PROPAGATION_REQUIRED (default) — join existing or create new read transaction
    }

    // -------------------------------------------------------------------------
    // T1 — recordJobStart
    // -------------------------------------------------------------------------

    override fun recordJobStart(metadata: AssetMetadataInput): JobStartResult {
        return UserContext.withThreadLocalUser(AUDIT_USER) {
            try {
                requireNotNull(
                    txTemplate.execute {
                        val existing = assetRepo.findByChecksum(metadata.checksum)
                        when (existing?.status) {
                            ImageAssetStatus.READY -> {
                                JobStartResult.AlreadyReady(
                                    assetId = existing.id,
                                    externalId = existing.externalId,
                                )
                            }

                            ImageAssetStatus.PROCESSING -> {
                                log.info { "Concurrent processing detected for checksum=${metadata.checksum}" }
                                val jobId = jobRepo.insertJob(existing.id, emptyList())
                                JobStartResult.ConcurrentProcessing(
                                    assetId = existing.id,
                                    jobId = jobId,
                                    externalId = existing.externalId,
                                )
                            }

                            ImageAssetStatus.FAILED -> {
                                assetRepo.updateStatus(existing.id, ImageAssetStatus.PROCESSING)
                                val jobId = jobRepo.insertJob(existing.id, emptyList())
                                JobStartResult.RecoveredFromFailed(
                                    assetId = existing.id,
                                    jobId = jobId,
                                    externalId = existing.externalId,
                                )
                            }

                            null -> {
                                // New asset — insert and create first job
                                val externalId = UUID.randomUUID().toString()
                                val assetId = ImageAssetTable.insertAndGetId {
                                    it[ImageAssetTable.externalId] = externalId
                                    it[ImageAssetTable.originalFilename] = metadata.originalFilename
                                    it[ImageAssetTable.contentType] = metadata.contentType
                                    it[ImageAssetTable.byteSize] = metadata.byteSize
                                    it[ImageAssetTable.width] = metadata.dimensions?.width
                                    it[ImageAssetTable.height] = metadata.dimensions?.height
                                    it[ImageAssetTable.checksum] = metadata.checksum
                                    it[ImageAssetTable.status] = ImageAssetStatus.PROCESSING
                                }.value
                                val jobId = jobRepo.insertJob(assetId, emptyList())
                                JobStartResult.NewAsset(
                                    assetId = assetId,
                                    jobId = jobId,
                                    externalId = externalId,
                                )
                            }
                        }
                    }
                ) { "T1 transaction returned null — unexpected rollback" }
            } catch (e: DataIntegrityViolationException) {
                // Concurrent insert race — the failed transaction has already been rolled back.
                // Re-read the conflicting row and branch on its current status.
                log.warn { "DataIntegrityViolationException on recordJobStart checksum=${metadata.checksum}: ${e.message}" }
                val reRead = assetRepo.findByChecksum(metadata.checksum)
                    ?: throw ImageAssetNotFoundException(metadata.checksum)
                when (reRead.status) {
                    ImageAssetStatus.READY -> JobStartResult.AlreadyReady(
                        assetId = reRead.id,
                        externalId = reRead.externalId,
                    )

                    ImageAssetStatus.PROCESSING -> {
                        val jobId = requireNotNull(txTemplate.execute {
                            jobRepo.insertJob(reRead.id, emptyList())
                        }) { "DIVE recovery — job insert transaction returned null" }
                        JobStartResult.ConcurrentProcessing(
                            assetId = reRead.id,
                            jobId = jobId,
                            externalId = reRead.externalId,
                        )
                    }

                    ImageAssetStatus.FAILED -> {
                        requireNotNull(txTemplate.execute {
                            assetRepo.updateStatus(reRead.id, ImageAssetStatus.PROCESSING)
                            val jobId = jobRepo.insertJob(reRead.id, emptyList())
                            JobStartResult.RecoveredFromFailed(
                                assetId = reRead.id,
                                jobId = jobId,
                                externalId = reRead.externalId,
                            )
                        }) { "DIVE recovery — failed-reset transaction returned null" }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // T2 — recordJobSuccess
    // -------------------------------------------------------------------------

    override fun recordJobSuccess(identity: JobIdentity, objects: List<ImageObjectInput>) {
        UserContext.withThreadLocalUser(AUDIT_USER) {
            txTemplate.executeWithoutResult {
                // Compute duration from job's startedAt before marking it finished.
                val job = jobRepo.findByIdOrNull(identity.jobId)
                val durationMs = if (job != null) {
                    ChronoUnit.MILLIS.between(job.startedAt, LocalDateTime.now(ZoneOffset.UTC))
                        .coerceAtLeast(0L)
                } else {
                    0L
                }
                objectRepo.batchUpsertObjects(identity.assetId, objects)
                assetRepo.updateStatus(identity.assetId, ImageAssetStatus.READY)
                jobRepo.markSucceeded(identity.jobId, durationMs)
            }
        }
    }

    // -------------------------------------------------------------------------
    // T3 — recordJobFailure
    // -------------------------------------------------------------------------

    override fun recordJobFailure(identity: JobIdentity, reason: JobFailureReason, durationMs: Long) {
        UserContext.withThreadLocalUser(AUDIT_USER) {
            txTemplate.executeWithoutResult {
                jobRepo.markFailed(identity.jobId, reason.errorCode, reason.errorMessage, durationMs)
                assetRepo.updateStatus(identity.assetId, ImageAssetStatus.FAILED)
            }
        }
    }

    // -------------------------------------------------------------------------
    // appendEvent
    // -------------------------------------------------------------------------

    override fun appendEvent(
        jobId: Long,
        step: ImageProcessingStep,
        status: ImageProcessingEventStatus,
        message: String,
        payload: Map<String, Any?>,
    ) {
        UserContext.withThreadLocalUser(AUDIT_USER) {
            txTemplate.executeWithoutResult {
                eventRepo.appendEvent(jobId, step, status, message, payload)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    override fun findAssetByExternalId(externalId: String): ImageAssetDetailResponse? =
        readTxTemplate.execute {
            val asset = assetRepo.findByExternalId(externalId) ?: return@execute null
            val objects = objectRepo.findByAssetId(asset.id)
            val original = objects.firstOrNull { it.kind == ImageObjectKind.ORIGINAL }
            val variants = objects.filter { it.kind == ImageObjectKind.VARIANT }
            ImageAssetDetailResponse(
                imageId = asset.externalId,
                status = asset.status,
                original = original,
                variants = variants,
            )
        }

    override fun findAssetHistory(externalId: String): ImageAssetHistoryResponse? =
        readTxTemplate.execute {
            val asset = assetRepo.findByExternalId(externalId) ?: return@execute null
            val jobs = jobRepo.findByAssetId(asset.id)
            val jobsWithEvents = jobs.map { job ->
                val events = eventRepo.findByJobId(job.id)
                ImageJobWithEventsDTO(job = job, events = events)
            }
            ImageAssetHistoryResponse(
                imageId = asset.externalId,
                jobs = jobsWithEvents,
            )
        }
}
