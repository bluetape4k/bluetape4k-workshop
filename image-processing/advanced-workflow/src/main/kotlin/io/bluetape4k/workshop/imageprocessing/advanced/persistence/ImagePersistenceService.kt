package io.bluetape4k.workshop.imageprocessing.advanced.persistence

import io.bluetape4k.exposed.core.auditable.UserContext
import io.bluetape4k.workshop.imageprocessing.advanced.model.AssetMetadataInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetDetailResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageAssetHistoryResponse
import io.bluetape4k.workshop.imageprocessing.advanced.model.ImageObjectInput
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobFailureReason
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobIdentity
import io.bluetape4k.workshop.imageprocessing.advanced.model.JobStartResult
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingEventStatus
import io.bluetape4k.workshop.imageprocessing.advanced.persistence.schema.ImageProcessingStep
import org.springframework.transaction.support.TransactionTemplate

/**
 * Service contract for the image persistence saga.
 *
 * ## Transaction contract
 * Each method runs in its own `REQUIRES_NEW` transaction via programmatic
 * [TransactionTemplate]. No `@Transactional` is placed on this interface or
 * its implementation — callers must NOT assume outer-transaction participation.
 *
 * ## Audit user
 * All write methods must be called within a [UserContext.withUser] block.
 * The implementation wraps the entire method body so callers do not need
 * to set the user context themselves.
 */
interface ImagePersistenceService {

    /**
     * T1: Starts a new processing job for the given checksum/metadata.
     *
     * Returns a [JobStartResult] describing whether a new asset was created,
     * an existing READY asset was found ([JobStartResult.AlreadyReady]), etc.
     * Handles [org.springframework.dao.DataIntegrityViolationException] for concurrent inserts.
     */
    fun recordJobStart(metadata: AssetMetadataInput): JobStartResult

    /**
     * T2: Marks a job as succeeded, upserts image objects, and updates asset status to READY.
     */
    fun recordJobSuccess(identity: JobIdentity, objects: List<ImageObjectInput>)

    /**
     * T3: Marks a job as failed and updates asset status to FAILED.
     *
     * Stores sanitized error info only — no raw stack traces.
     * The [kotlinx.coroutines.NonCancellable] + [kotlinx.coroutines.Dispatchers.IO] wrapping
     * is the CALLER's responsibility.
     */
    fun recordJobFailure(identity: JobIdentity, reason: JobFailureReason, durationMs: Long)

    /**
     * Appends a single processing event row.
     *
     * Exceptions propagate to caller — no suppression inside implementation.
     */
    fun appendEvent(
        jobId: Long,
        step: ImageProcessingStep,
        status: ImageProcessingEventStatus,
        message: String,
        payload: Map<String, Any?> = emptyMap(),
    )

    /**
     * Returns asset detail (original + variants) by [externalId].
     *
     * Returns null if no asset exists for the given ID.
     */
    fun findAssetByExternalId(externalId: String): ImageAssetDetailResponse?

    /**
     * Returns the full job + event history for the asset identified by [externalId].
     *
     * Returns null if no asset exists.
     */
    fun findAssetHistory(externalId: String): ImageAssetHistoryResponse?
}
