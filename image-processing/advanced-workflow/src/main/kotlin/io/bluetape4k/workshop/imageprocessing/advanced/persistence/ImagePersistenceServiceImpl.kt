package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.logging.warn
import io.bluetape4k.support.requireNotNull
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
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.ImagePersistenceServiceImpl.Companion.AUDIT_USER
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
 * ## 감사 사용자
 * 모든 쓰기 메서드는 [AUDIT_USER]와 함께 [UserContext.withThreadLocalUser]로 본문 전체를 감쌉니다.
 * 그래서 감사 컬럼(`created_by`, `updated_by`)이 올바르게 채워집니다.
 * [UserContext.withUser] 대신 [UserContext.withThreadLocalUser]를 사용합니다. 이유는
 * 이 서비스가 ScopedValue와 호환되지 않는 코루틴 dispatcher(Dispatchers.IO)에서
 * 호출되기 때문입니다(withUser 내부에서 ScopedValue 사용).
 * ## 동시성
 * [recordJobStart]는 [DataIntegrityViolationException](DIVE)을 [txTemplate]
 * 블록 밖, 즉 실패한 트랜잭션이 rollback된 뒤에 잡고
 * 충돌한 행을 다시 읽어 그 상태에 따라 분기합니다.
 * @property assetRepo 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property objectRepo 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property jobRepo 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 * @property eventRepo 이미지 처리 경계의 전달 데이터입니다. 스키마·크기·무결성을 검증한 뒤 저장하거나 발행합니다.
 */
@Service
class ImagePersistenceServiceImpl(
    transactionManager: PlatformTransactionManager,
    /**
     * `assetRepo`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val assetRepo: ImageAssetRepository,
    /**
     * `objectRepo`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
     *
     * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
     */
    private val objectRepo: ImageObjectRepository,
    /**
     * `jobRepo`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
     *
     * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
     */
    private val jobRepo: ImageProcessingJobRepository,
    /**
     * `eventRepo`은 이미지 업로드·파생 처리에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
     *
     * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
     */
    private val eventRepo: ImageProcessingEventRepository,
): ImagePersistenceService {

    companion object: KLogging() {
        /**
         * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
         */
        private const val AUDIT_USER = "image-processing-service"
    }
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val txTemplate = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val readTxTemplate = TransactionTemplate(transactionManager).apply {
        isReadOnly = true
        // PROPAGATION_REQUIRED(기본값) — 기존 트랜잭션에 참여하거나 새 읽기 트랜잭션을 만듭니다.
    }

    // -------------------------------------------------------------------------
    // T1 — recordJobStart
    // -------------------------------------------------------------------------

    /**
     * recordJobStart은 이미지 업로드·검수·파생물 처리 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param metadata 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
    override fun recordJobStart(metadata: AssetMetadataInput): JobStartResult {
        return UserContext.withThreadLocalUser(AUDIT_USER) {
            try {
                txTemplate.execute {
                    val existing = assetRepo.findByChecksum(metadata.checksum)
                    when (existing?.status) {
                        ImageAssetStatus.READY      -> {
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

                        ImageAssetStatus.FAILED     -> {
                            log.info { "Recovery failed for checksum=${metadata.checksum}" }
                            assetRepo.updateStatus(existing.id, ImageAssetStatus.PROCESSING)
                            val jobId = jobRepo.insertJob(existing.id, emptyList())
                            JobStartResult.RecoveredFromFailed(
                                assetId = existing.id,
                                jobId = jobId,
                                externalId = existing.externalId,
                            )
                        }

                        null                        -> {
                            // 새 asset — insert하고 첫 job을 만듭니다.
                            log.info { "Create new asset for checksum=${metadata.checksum}" }
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
                }.requireNotNull { "T1 transaction returned null — unexpected rollback" }
            } catch (e: DataIntegrityViolationException) {
                // 동시 insert 경합 — 실패한 트랜잭션은 이미 rollback되었습니다.
                // 충돌한 행을 다시 읽고 현재 상태에 따라 분기합니다.
                log.warn { "DataIntegrityViolationException on recordJobStart checksum=${metadata.checksum}: ${e.message}" }
                val reRead = assetRepo.findByChecksum(metadata.checksum)
                    ?: throw ImageAssetNotFoundException(metadata.checksum)
                when (reRead.status) {
                    ImageAssetStatus.READY      -> JobStartResult.AlreadyReady(
                        assetId = reRead.id,
                        externalId = reRead.externalId,
                    )

                    ImageAssetStatus.PROCESSING -> {
                        val jobId = txTemplate.execute {
                            jobRepo.insertJob(reRead.id, emptyList())
                        }.requireNotNull { "DIVE recovery — job insert transaction returned null" }
                        JobStartResult.ConcurrentProcessing(
                            assetId = reRead.id,
                            jobId = jobId,
                            externalId = reRead.externalId,
                        )
                    }

                    ImageAssetStatus.FAILED     -> {
                        txTemplate.execute {
                            assetRepo.updateStatus(reRead.id, ImageAssetStatus.PROCESSING)
                            val jobId = jobRepo.insertJob(reRead.id, emptyList())
                            JobStartResult.RecoveredFromFailed(
                                assetId = reRead.id,
                                jobId = jobId,
                                externalId = reRead.externalId,
                            )
                        }.requireNotNull { "DIVE recovery — failed-reset transaction returned null" }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // T2 — recordJobSuccess
    // -------------------------------------------------------------------------

    /**
     * recordJobSuccess은 이미지 업로드·검수·파생물 처리 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param objects 이미지 업로드·검수·파생물 처리 처리 대상의 위치·콘텐츠 참조입니다. 허용된 형식과 보안 정책을 먼저 확인합니다.
     */
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

    /**
     * recordJobFailure은 이미지 업로드·검수·파생물 처리 업무 명령을 접수해 입력을 검증하고, 저장소의 확정 상태와 후속 이벤트가 같은 revision을
     * 가리키도록 처리합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param identity 업무 처리에 필요한 identity 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param reason 업무 처리에 필요한 reason 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param durationMs 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     */
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

    /**
     * appendEvent은 이미지 업로드·검수·파생물 처리 변경을 outbox·이벤트 계약으로 전달하되 commit 순서, partition key, 중복 소비 방지와
     * 재전송 결과를
     * 보존합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param jobId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     * @param step 업무 처리에 필요한 step 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     * @param status 업무 판정에 사용하는 기준 시각입니다. 테스트·재시도에서도 동일한 시간 규칙을 적용합니다.
     * @param message 이미지 업로드·검수·파생물 처리 상태 변경을 나타내는 이벤트 또는 저장 레코드입니다. 순서·revision·중복 여부를 보존해야 합니다.
     * @param payload 이미지 업로드·검수·파생물 처리 명령의 입력입니다. 스키마·필수값·중복 키를 검증한 뒤 부작용을 시작합니다.
     */
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

    /**
     * findAssetByExternalId은 이미지 업로드·검수·파생물 처리의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로
     * 현재 상태를
     * 추정하지 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param externalId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun findAssetByExternalId(externalId: String): ImageAssetDetailResponse? =
        readTxTemplate.execute {
            /**
             * `asset`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val asset = assetRepo.findByExternalId(externalId) ?: return@execute null
            /**
             * `objects`은 이미지 업로드·파생 처리에서 저장 객체·외부 리소스·전송 경계를 가리키는 참조 값입니다.
             *
             * allowlist·sanitize·소유권 검사를 거친 뒤 API·저장소·이벤트에서 동일한 key 규칙을 사용합니다.
             */
            val objects = objectRepo.findByAssetId(asset.id)
                .onEach { log.debug { "ImageObjectDTO=$it" } }

            /**
             * `original`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val original = objects.firstOrNull { it.kind == ImageObjectKind.ORIGINAL }
            /**
             * `variants`은 이미지 업로드·파생 처리에서 업무 집합 또는 조회 결과입니다.
             *
             * 빈 집합·중복·정렬·페이지 경계를 정의하고 기준 저장소의 순서를 그대로 보존합니다.
             */
            val variants = objects.filter { it.kind == ImageObjectKind.VARIANT }
            ImageAssetDetailResponse(
                imageId = asset.externalId,
                status = asset.status,
                original = original,
                variants = variants,
            )
        }

    /**
     * findAssetHistory은 이미지 업로드·검수·파생물 처리의 commit된 source-of-truth를 읽어 호출자에게 반환하며, 알림이나 캐시만으로 현재
     * 상태를 추정하지
     * 않습니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param externalId 처리할 이미지 업로드·검수·파생물 처리 업무 객체의 식별자입니다. 존재 여부와 호출자의 접근 범위를 함께 확인합니다.
     */
    override fun findAssetHistory(externalId: String): ImageAssetHistoryResponse? =
        readTxTemplate.execute {
            /**
             * `asset`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val asset = assetRepo.findByExternalId(externalId) ?: return@execute null
            /**
             * `jobs`은 이미지 업로드·파생 처리에서 업무 흐름을 구성하는 입력 또는 결과 값입니다.
             *
             * 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이에서 이 의미를 보존합니다.
             */
            val jobs = jobRepo.findByAssetId(asset.id)
            /**
             * `jobsWithEvents`은 이미지 업로드·파생 처리에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
             *
             * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
             */
            val jobsWithEvents = jobs.map { job ->
                /**
                 * `events`은 이미지 업로드·파생 처리에서 서비스 경계와 교환하는 내구성 메시지 또는 업무 사실입니다.
                 *
                 * at-least-once 전달과 중복·순서 뒤바뀜을 고려해 digest·idempotency·quarantine 규칙을 적용합니다.
                 */
                val events = eventRepo.findByJobId(job.id)
                ImageJobWithEventsDTO(job = job, events = events)
            }
            ImageAssetHistoryResponse(
                imageId = asset.externalId,
                jobs = jobsWithEvents,
            )
        }
}
