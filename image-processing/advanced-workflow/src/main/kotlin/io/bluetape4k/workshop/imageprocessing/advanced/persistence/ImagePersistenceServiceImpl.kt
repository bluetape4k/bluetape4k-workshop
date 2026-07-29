package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.idgenerators.uuid.Uuid
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

/**
 * [ImagePersistenceService]의 프로그래밍 방식 트랜잭션 구현입니다.
 *
 * ## 트랜잭션 전략
 * 각 쓰기 메서드는 [txTemplate]을 통해 자체 `REQUIRES_NEW` 트랜잭션 안에서 실행됩니다.
 * 어떤 메서드에도 `@Transactional` 애너테이션을 두지 않으며 모든 트랜잭션
 * 경계는 명시적으로 관리합니다.
 *
 * ## 감사 사용자
 * 모든 쓰기 메서드는 [AUDIT_USER]와 함께 [UserContext.withThreadLocalUser]로 본문 전체를 감쌉니다.
 * 그래서 감사 컬럼(`created_by`, `updated_by`)이 올바르게 채워집니다.
 * [UserContext.withUser] 대신 [UserContext.withThreadLocalUser]를 사용합니다. 이유는
 * 이 서비스가 ScopedValue와 호환되지 않는 코루틴 dispatcher(Dispatchers.IO)에서
 * 호출되기 때문입니다(withUser 내부에서 ScopedValue 사용).
 *
 * ## 동시성
 * [recordJobStart]는 [DataIntegrityViolationException](DIVE)을 [txTemplate]
 * 블록 밖, 즉 실패한 트랜잭션이 rollback된 뒤에 잡고
 * 충돌한 행을 다시 읽어 그 상태에 따라 분기합니다.
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
        // PROPAGATION_REQUIRED(기본값) — 기존 트랜잭션에 참여하거나 새 읽기 트랜잭션을 만듭니다.
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
                                // 새 asset — insert하고 첫 job을 만듭니다.
                                val externalId = Uuid.V7.nextIdAsString()
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
                // 동시 insert 경합 — 실패한 트랜잭션은 이미 rollback되었습니다.
                // 충돌한 행을 다시 읽고 현재 상태에 따라 분기합니다.
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
                // job을 완료로 표시하기 전에 startedAt 기준으로 소요 시간을 계산합니다.
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
    // 조회
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
