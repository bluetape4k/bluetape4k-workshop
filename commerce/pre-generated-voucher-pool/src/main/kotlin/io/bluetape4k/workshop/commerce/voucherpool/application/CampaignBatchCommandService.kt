@file:Suppress(
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "ReturnCount",
    "TooManyFunctions",
)

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CanonicalVoucherCode
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.VoucherPoolErrorCode
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.EffectReference
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyOwner
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.BatchRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.CampaignRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.PreparedVoucherEntryRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.RejectedVoucherEntryRecord
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.security.EntryIdentity
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherCryptoException
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherEnvelopeCrypto
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.currentOrNull
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

internal enum class BatchSourceKind { IMPORTED, GENERATED }

internal fun releaseRetryableOwner(release: () -> Unit) {
    var lastTimeout: VoucherPoolJdbcTimeoutException? = null
    repeat(MAX_OWNER_RELEASE_ATTEMPTS) {
        try {
            release()
            return
        } catch (failure: VoucherPoolJdbcTimeoutException) {
            lastTimeout = failure
            Thread.yield()
        }
    }
    throw checkNotNull(lastTimeout)
}

private const val MAX_OWNER_RELEASE_ATTEMPTS = 8

internal enum class BatchCommandFailure {
    SCOPE_NOT_FOUND,
    STALE_REVISION,
    INVALID_STATE,
    ORDINAL_GAP,
    CHUNK_FINGERPRINT_CONFLICT,
    DUPLICATE_CODE,
    ACTIVATION_INCOMPLETE,
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    COMMAND_IN_PROGRESS,
    REPLAY_WINDOW_EXPIRED,
    CREATE_FINGERPRINT_CONFLICT,
    RETRYABLE_INTEGRITY_COLLISION,
    VALIDATION_REJECTED,
}

internal open class BatchCommandException(val reason: BatchCommandFailure) : IllegalStateException(reason.name)

internal class PreparedChunkRejectedException(
    val evidenceCode: String,
    val rejectedCount: Long,
    val nextSourceOrdinal: Long,
) : BatchCommandException(BatchCommandFailure.VALIDATION_REJECTED)

internal sealed interface MutationResult<out T> {
    data class Applied<T>(val value: T) : MutationResult<T>
    data class Replay(val descriptor: SafeResponseDescriptor) : MutationResult<Nothing>
    data class Expired(val effectId: UUID?, val terminalCode: VoucherPoolErrorCode?) : MutationResult<Nothing>
}

internal enum class VoucherPoolRuntimeProfile(val permitsDeterministicGeneration: Boolean) {
    PRODUCTION(false),
    LOOPBACK_TEST(true),
    DEMO(true),
}

internal data class CampaignSnapshot(
    val tenantId: String,
    val campaignId: UUID,
    val state: CampaignState,
    val policyVersion: Long,
    val revision: Long,
)

internal data class BatchSnapshot(
    val tenantId: String,
    val batchId: UUID,
    val campaignId: UUID,
    val state: BatchState,
    val nextSourceOrdinal: Long,
    val expectedCount: Long,
    val acceptedCount: Long,
    val rejectedCount: Long,
    val checkpointDigest: DigestValue?,
    val lastFailureCode: String?,
    val revision: Long,
)

internal data class CreateCampaignCommand(
    val tenantId: String,
    val campaignId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val policy: VoucherPoolPolicy,
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(startsAt < endsAt) { "campaign start must precede end" }
    }
}

internal data class UpdateCampaignPolicyCommand(
    val tenantId: String,
    val campaignId: UUID,
    val expectedRevision: Long,
    val policy: VoucherPoolPolicy,
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
    }
}

internal data class CampaignRevisionCommand(
    val tenantId: String,
    val campaignId: UUID,
    val expectedRevision: Long,
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
    }
}

internal class CreateImportBatchCommand(
    val tenantId: String,
    val batchId: UUID,
    val campaignId: UUID,
    val sourceKind: BatchSourceKind,
    val manifestDigest: DigestValue,
    val requestFingerprint: DigestValue,
    val expectedCount: Long,
    val activatesAt: Instant,
    val expiresAt: Instant? = null,
    initialCodes: List<String>,
    val idempotencyKey: String,
) {
    val initialCodes: List<String> = initialCodes.toList()

    init {
        requireTenant(tenantId)
        require(expectedCount in 1..MAX_BATCH_ENTRIES) { "expectedCount must be in 1..$MAX_BATCH_ENTRIES" }
        require(this.initialCodes.size <= expectedCount) { "initial chunk exceeds expectedCount" }
        if (sourceKind == BatchSourceKind.IMPORTED) require(this.initialCodes.isNotEmpty()) {
            "import batch requires its first chunk"
        }
        if (sourceKind == BatchSourceKind.GENERATED) require(this.initialCodes.isEmpty()) {
            "generated batch must not accept caller codes"
        }
        validateChunkPayload(this.initialCodes, allowEmpty = sourceKind == BatchSourceKind.GENERATED)
        require(expiresAt == null || expiresAt > activatesAt) { "batch expiry must follow activation" }
    }
}

internal class ImportChunkCommand(
    val tenantId: String,
    val batchId: UUID,
    val campaignId: UUID,
    val firstOrdinal: Long,
    val manifestDigest: DigestValue,
    codes: List<String>,
    val expectedRevision: Long,
    val idempotencyKey: String,
) {
    val codes: List<String> = codes.toList()

    init {
        requireTenant(tenantId)
        require(firstOrdinal >= 0) { "firstOrdinal must not be negative" }
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
        validateChunkPayload(this.codes)
    }
}

internal data class GenerateChunkCommand(
    val tenantId: String,
    val batchId: UUID,
    val campaignId: UUID,
    val firstOrdinal: Long,
    val manifestDigest: DigestValue,
    val count: Int,
    val expectedRevision: Long,
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(firstOrdinal >= 0) { "firstOrdinal must not be negative" }
        require(count in 1..MAX_CHUNK_ENTRIES) { "generation count must be in 1..$MAX_CHUNK_ENTRIES" }
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
    }
}

internal data class BatchRevisionCommand(
    val tenantId: String,
    val campaignId: UUID,
    val batchId: UUID,
    val expectedRevision: Long,
    val idempotencyKey: String,
) {
    init {
        requireTenant(tenantId)
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
    }
}

internal interface GeneratedVoucherCodeSource {
    val deterministic: Boolean
    fun nextCode(): String
}

internal class SecureRandomVoucherCodeSource(private val random: SecureRandom = SecureRandom()) :
    GeneratedVoucherCodeSource {
    override val deterministic: Boolean = false

    override fun nextCode(): String {
        val entropy = ByteArray(GENERATED_ENTROPY_BYTES).also(random::nextBytes)
        return try {
            "VP-" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy)
        } finally {
            entropy.fill(0)
        }
    }

    override fun toString(): String = "SecureRandomVoucherCodeSource([REDACTED])"
}

internal interface CampaignBatchCommandService {
    fun createCampaign(command: CreateCampaignCommand): MutationResult<CampaignSnapshot>
    fun updatePolicy(command: UpdateCampaignPolicyCommand): MutationResult<CampaignSnapshot>
    fun activateCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot>
    fun pauseCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot>
    fun resumeCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot>
    fun createImportBatch(command: CreateImportBatchCommand): MutationResult<BatchSnapshot>
    fun importChunk(command: ImportChunkCommand): MutationResult<BatchSnapshot>
    fun generateChunk(command: GenerateChunkCommand): MutationResult<BatchSnapshot>
    fun activateBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot>
    fun pauseBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot>
    fun resumeBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot>
}

/** Bounded application orchestration; all parsing and encryption precedes JDBC admission. */
@Suppress("LargeClass") // Task-scoped command contracts and their shared idempotency boundary remain co-located.
internal class JdbcCampaignBatchCommandService(
    private val executor: VoucherPoolJdbcExecutor,
    private val repository: VoucherPoolRepository,
    private val idempotency: VoucherPoolIdempotencyRepository,
    private val digests: VoucherDigestService,
    private val crypto: VoucherEnvelopeCrypto,
    private val generatedCodes: GeneratedVoucherCodeSource = SecureRandomVoucherCodeSource(),
    runtimeProfile: VoucherPoolRuntimeProfile = VoucherPoolRuntimeProfile.PRODUCTION,
) : CampaignBatchCommandService {
    init {
        require(!generatedCodes.deterministic || runtimeProfile.permitsDeterministicGeneration) {
            "deterministic voucher generation is restricted to loopback test or demo mode"
        }
    }

    override fun createCampaign(command: CreateCampaignCommand): MutationResult<CampaignSnapshot> = executeIdempotent(
        command.tenantId,
        command.idempotencyKey,
        OP_CREATE_CAMPAIGN,
        command.fingerprint(),
        command.campaignId,
        HTTP_CREATED,
        "CAMPAIGN_CREATED",
        MutationLane.OPERATOR,
        failureMapper = ::classifyIntegrityFailure,
        createRecovery = { connection ->
            repository.lockCampaignForUpdate(connection, command.tenantId, command.campaignId)
                ?.takeIf { it.sameCreateAuthority(requestedCampaign(command)) }
                ?.snapshot()
        },
    ) { connection ->
        val requested = requestedCampaign(command)
        repository.lockCampaignForUpdate(connection, command.tenantId, command.campaignId)?.let { existing ->
            if (!existing.sameCreateAuthority(requested)) fail(BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT)
            return@executeIdempotent existing.snapshot()
        }
        repository.createCampaign(connection, requested).snapshot()
    }

    override fun updatePolicy(command: UpdateCampaignPolicyCommand): MutationResult<CampaignSnapshot> = executeIdempotent(
        command.tenantId,
        command.idempotencyKey,
        OP_UPDATE_CAMPAIGN_POLICY,
        command.fingerprint(),
        command.campaignId,
        HTTP_OK,
        "CAMPAIGN_POLICY_UPDATED",
        MutationLane.OPERATOR,
    ) { connection ->
        val current = repository.lockCampaignForUpdate(connection, command.tenantId, command.campaignId)
            ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
        requireRevision(current.revision, command.expectedRevision)
        if (current.state !in setOf(CampaignState.DRAFT, CampaignState.PAUSED)) {
            fail(BatchCommandFailure.INVALID_STATE)
        }
        repository.updateCampaign(
            connection,
            current.copy(
                perUserLimit = command.policy.perUserLimit,
                reservationTtlSeconds = command.policy.reservationTtl.inWholeSeconds,
                allocationTtlSeconds = command.policy.allocationTtl.inWholeSeconds,
                replacementAllowance = command.policy.replacementAllowance,
                policyVersion = current.policyVersion + 1,
            ),
            current.revision,
        ).snapshot()
    }

    override fun activateCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot> = executeIdempotent(
        command.tenantId,
        command.idempotencyKey,
        OP_ACTIVATE_CAMPAIGN,
        command.fingerprint(OP_ACTIVATE_CAMPAIGN),
        command.campaignId,
        HTTP_OK,
        "CAMPAIGN_ACTIVATED",
        MutationLane.OPERATOR,
    ) { connection ->
        val current = repository.lockCampaignForUpdate(connection, command.tenantId, command.campaignId)
            ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
        requireRevision(current.revision, command.expectedRevision)
        if (!CampaignPolicy.canTransition(current.state, CampaignState.ACTIVE)) {
            fail(BatchCommandFailure.INVALID_STATE)
        }
        repository.updateCampaign(connection, current.copy(state = CampaignState.ACTIVE), current.revision).snapshot()
    }

    override fun pauseCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot> =
        transitionCampaign(command, CampaignState.PAUSED, OP_PAUSE_CAMPAIGN, "CAMPAIGN_PAUSED")

    override fun resumeCampaign(command: CampaignRevisionCommand): MutationResult<CampaignSnapshot> =
        transitionCampaign(command, CampaignState.ACTIVE, OP_RESUME_CAMPAIGN, "CAMPAIGN_RESUMED")

    override fun createImportBatch(command: CreateImportBatchCommand): MutationResult<BatchSnapshot> {
        val prepared = prepare(command.tenantId, command.campaignId, command.batchId, 0, command.initialCodes)
        return executeIdempotent(
            command.tenantId,
            command.idempotencyKey,
            OP_CREATE_BATCH,
            command.fingerprint(prepared),
            command.batchId,
            HTTP_CREATED,
            "BATCH_CREATED",
            MutationLane.OPERATOR,
            failureMapper = ::classifyIntegrityFailure,
            terminalEffect = { connection, failure ->
                markTerminalBatchFailure(
                    connection,
                    command.tenantId,
                    command.campaignId,
                    command.batchId,
                    failure,
                    command.manifestDigest,
                    0,
                ) { campaign -> requestedBatch(command, campaign.policyVersion) }
            },
            createRecovery = { connection ->
                val campaign = repository.lockCampaignForShare(connection, command.tenantId, command.campaignId)
                repository.lockBatchForUpdate(connection, command.tenantId, command.batchId)
                    ?.takeIf { existing ->
                        existing.sameCreateAuthority(requestedBatch(command, campaign.policyVersion)) &&
                            (prepared.totalCount == 0 || committedReplayExact(connection, existing, prepared))
                    }
                    ?.snapshot()
            },
        ) { connection ->
                val campaign = repository.lockCampaignForShare(connection, command.tenantId, command.campaignId)
                if (campaign.state != CampaignState.ACTIVE) fail(BatchCommandFailure.INVALID_STATE)
                val requested = requestedBatch(command, campaign.policyVersion)
                repository.lockBatchForUpdate(connection, command.tenantId, command.batchId)?.let { existing ->
                    if (!existing.sameCreateAuthority(requested)) fail(BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT)
                    if (prepared.totalCount > 0) replayCommitted(connection, existing, prepared)
                    return@executeIdempotent existing.snapshot()
                }
                var batch = repository.createBatch(connection, requested)
                if (prepared.totalCount > 0) {
                    batch = commitPrepared(connection, batch, command.manifestDigest, prepared)
                }
                batch.snapshot()
        }
    }

    override fun importChunk(command: ImportChunkCommand): MutationResult<BatchSnapshot> {
        val prepared = prepare(command.tenantId, command.campaignId, command.batchId, command.firstOrdinal, command.codes)
        return ingest(
            command.tenantId,
            command.campaignId,
            command.batchId,
            command.expectedRevision,
            command.idempotencyKey,
            command.fingerprint(prepared),
            command.manifestDigest,
            prepared,
            BatchSourceKind.IMPORTED,
        )
    }

    override fun generateChunk(command: GenerateChunkCommand): MutationResult<BatchSnapshot> {
        val codes = List(command.count) { generatedCodes.nextCode() }
        val prepared = prepare(command.tenantId, command.campaignId, command.batchId, command.firstOrdinal, codes)
        return ingest(
            command.tenantId,
            command.campaignId,
            command.batchId,
            command.expectedRevision,
            command.idempotencyKey,
            command.fingerprint(),
            command.manifestDigest,
            prepared,
            BatchSourceKind.GENERATED,
        )
    }

    override fun activateBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot> = executeIdempotent(
        command.tenantId,
        command.idempotencyKey,
        OP_ACTIVATE_BATCH,
        command.fingerprint(),
        command.batchId,
        HTTP_OK,
        "BATCH_ACTIVATED",
        MutationLane.OPERATOR,
    ) { connection ->
        val campaign = repository.lockCampaignForShare(connection, command.tenantId, command.campaignId)
        if (campaign.state != CampaignState.ACTIVE) fail(BatchCommandFailure.INVALID_STATE)
        val batch = repository.lockBatchForUpdate(connection, command.tenantId, command.batchId)
            ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
        requireBatchScope(batch, command.campaignId)
        requireRevision(batch.revision, command.expectedRevision)
        val coverage = repository.batchOrdinalCoverage(connection, command.tenantId, command.batchId)
        val canonicalCheckpoint = canonicalCheckpoint(connection, batch, batch.expectedCount)
        val complete = batch.state == BatchState.STAGING &&
            batch.nextSourceOrdinal == batch.expectedCount &&
            batch.acceptedCount == batch.expectedCount &&
            batch.rejectedCount == 0L &&
            batch.lastFailureCode == null &&
            coverage.entryCount == batch.expectedCount &&
            coverage.minimumOrdinal == 0L &&
            coverage.maximumOrdinal == batch.expectedCount - 1 &&
            batch.checkpointDigest?.secureEquals(canonicalCheckpoint) == true
        if (!complete || !BatchPolicy.canTransition(batch.state, BatchState.ACTIVE)) {
            fail(BatchCommandFailure.ACTIVATION_INCOMPLETE)
        }
        repository.activateBatch(connection, batch).snapshot()
    }

    override fun pauseBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot> =
        transitionBatch(command, BatchState.PAUSED, OP_PAUSE_BATCH, "BATCH_PAUSED")

    override fun resumeBatch(command: BatchRevisionCommand): MutationResult<BatchSnapshot> =
        transitionBatch(command, BatchState.ACTIVE, OP_RESUME_BATCH, "BATCH_RESUMED")

    private fun transitionCampaign(
        command: CampaignRevisionCommand,
        target: CampaignState,
        operation: String,
        outcome: String,
    ): MutationResult<CampaignSnapshot> = executeIdempotent(
        command.tenantId,
        command.idempotencyKey,
        operation,
        command.fingerprint(operation),
        command.campaignId,
        HTTP_OK,
        outcome,
        MutationLane.OPERATOR,
    ) { connection ->
        val campaign = repository.lockCampaignForUpdate(connection, command.tenantId, command.campaignId)
            ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
        requireRevision(campaign.revision, command.expectedRevision)
        if (!CampaignPolicy.canTransition(campaign.state, target)) fail(BatchCommandFailure.INVALID_STATE)
        repository.updateCampaign(connection, campaign.copy(state = target), campaign.revision).snapshot()
    }

    private fun transitionBatch(
        command: BatchRevisionCommand,
        target: BatchState,
        operation: String,
        outcome: String,
    ): MutationResult<BatchSnapshot> = executeIdempotent(
        command.tenantId,
        command.idempotencyKey,
        operation,
        command.fingerprint(operation),
        command.batchId,
        HTTP_OK,
        outcome,
        MutationLane.OPERATOR,
    ) { connection ->
        repository.lockCampaignForShare(connection, command.tenantId, command.campaignId)
        val batch = repository.lockBatchForUpdate(connection, command.tenantId, command.batchId)
            ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
        requireBatchScope(batch, command.campaignId)
        requireRevision(batch.revision, command.expectedRevision)
        if (!BatchPolicy.canTransition(batch.state, target)) fail(BatchCommandFailure.INVALID_STATE)
        repository.updateBatchState(connection, batch, target).snapshot()
    }

    private fun ingest(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        expectedRevision: Long,
        idempotencyKey: String,
        fingerprint: CommandFingerprint,
        manifestDigest: DigestValue,
        prepared: PreparedChunk,
        expectedSource: BatchSourceKind,
    ): MutationResult<BatchSnapshot> = executeIdempotent(
        tenantId,
        idempotencyKey,
        if (expectedSource == BatchSourceKind.IMPORTED) OP_IMPORT_CHUNK else OP_GENERATE_CHUNK,
        fingerprint,
        batchId,
        HTTP_OK,
        "BATCH_CHECKPOINTED",
        MutationLane.WORKER,
        failureMapper = ::classifyIntegrityFailure,
        terminalEffect = { connection, failure ->
            markTerminalBatchFailure(
                connection,
                tenantId,
                campaignId,
                batchId,
                failure,
                manifestDigest,
                prepared.firstOrdinal,
            )
        },
    ) { connection ->
            repository.lockCampaignForShare(connection, tenantId, campaignId)
            val batch = repository.lockBatchForUpdate(connection, tenantId, batchId)
                ?: fail(BatchCommandFailure.SCOPE_NOT_FOUND)
            requireBatchScope(batch, campaignId)
            requireRevision(batch.revision, expectedRevision)
            if (batch.sourceKind != expectedSource.name) fail(BatchCommandFailure.INVALID_STATE)
            commitPrepared(connection, batch, manifestDigest, prepared).snapshot()
    }

    private fun commitPrepared(
        connection: Connection,
        batch: BatchRecord,
        manifestDigest: DigestValue,
        prepared: PreparedChunk,
    ): BatchRecord {
        if (!MessageDigest.isEqual(batch.provenanceDigest.copyBytes(), manifestDigest.copyBytes())) {
            fail(BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT)
        }
        val chunkEnd = prepared.firstOrdinal + prepared.totalCount
        if (chunkEnd > batch.expectedCount) fail(BatchCommandFailure.ORDINAL_GAP)
        if (prepared.firstOrdinal < batch.nextSourceOrdinal) return replayCommitted(connection, batch, prepared)
        if (prepared.firstOrdinal > batch.nextSourceOrdinal) fail(BatchCommandFailure.ORDINAL_GAP)
        if (batch.state != BatchState.STAGING) fail(BatchCommandFailure.INVALID_STATE)

        prepared.rejections.firstOrNull()?.let { rejection ->
            throw PreparedChunkRejectedException(
                evidenceCode = rejection.evidenceCode(),
                rejectedCount = prepared.totalCount.toLong(),
                nextSourceOrdinal = chunkEnd,
            )
        }
        repository.insertPreparedEntries(connection, prepared.entries)
        val nextCheckpoint = prepared.entries.fold(batch.checkpointDigest ?: initialCheckpoint(batch)) { previous, entry ->
            advanceCheckpoint(previous, entry.sourceOrdinal, entry.stableDedupDigest)
        }
        return repository.updateBatchCheckpoint(
            connection = connection,
            batch = batch,
            nextSourceOrdinal = chunkEnd,
            acceptedCount = batch.acceptedCount + prepared.entries.size,
            rejectedCount = batch.rejectedCount,
            checkpointDigest = nextCheckpoint,
            state = BatchState.STAGING,
            lastFailureCode = null,
        )
    }

    private fun replayCommitted(
        connection: Connection,
        batch: BatchRecord,
        prepared: PreparedChunk,
    ): BatchRecord {
        if (prepared.rejections.isNotEmpty() || prepared.firstOrdinal + prepared.totalCount > batch.nextSourceOrdinal) {
            fail(BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT)
        }
        if (!committedReplayExact(connection, batch, prepared)) fail(BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT)
        return batch
    }

    private fun committedReplayExact(
        connection: Connection,
        batch: BatchRecord,
        prepared: PreparedChunk,
    ): Boolean {
        val stored = repository.committedOrdinalDigests(
            connection,
            batch.tenantId,
            batch.batchId,
            prepared.firstOrdinal,
            prepared.totalCount,
        )
        return stored.size == prepared.entries.size && stored.zip(prepared.entries).all { (authority, candidate) ->
            authority.sourceOrdinal == candidate.sourceOrdinal &&
                MessageDigest.isEqual(authority.stableDedupDigest.copyBytes(), candidate.stableDedupDigest.copyBytes())
        }
    }

    private fun prepare(
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        firstOrdinal: Long,
        codes: List<String>,
    ): PreparedChunk {
        val entries = ArrayList<PreparedVoucherEntryRecord>(codes.size)
        val rejections = ArrayList<RejectedVoucherEntryRecord>()
        codes.forEachIndexed { index, rawCode ->
            val ordinal = firstOrdinal + index
            val payloadDigest = DigestValue.of(digests.audit(tenantId, INVALID_CODE_AUDIT_OPERATION, rawCode).copyBytes())
            try {
                val code = CanonicalVoucherCode.of(rawCode)
                val stable = digests.stableDedup(tenantId, code)
                val entryId = UUID.randomUUID()
                val encrypted = crypto.encrypt(EntryIdentity(tenantId, campaignId, batchId, entryId, ordinal), code)
                entries += PreparedVoucherEntryRecord(
                    tenantId,
                    campaignId,
                    batchId,
                    entryId,
                    ordinal,
                    DigestValue.of(stable.copyBytes()),
                    stable.keyVersion,
                    DigestValue.of(encrypted.copyCodeCiphertext()),
                    DigestValue.of(encrypted.copyCodeNonce()),
                    DigestValue.of(encrypted.copyWrappedDek()),
                    DigestValue.of(encrypted.copyWrapNonce()),
                    encrypted.kekVersion,
                )
            } catch (_: IllegalArgumentException) {
                rejections += RejectedVoucherEntryRecord(ordinal, "INVALID_CODE", payloadDigest)
            } catch (_: VoucherCryptoException) {
                rejections += RejectedVoucherEntryRecord(ordinal, "CRYPTO_FAILURE", payloadDigest)
            }
        }
        return PreparedChunk(firstOrdinal, codes.size, entries, rejections)
    }

    private fun canonicalCheckpoint(connection: Connection, batch: BatchRecord, count: Long): DigestValue {
        require(count in 0..MAX_BATCH_ENTRIES)
        var checkpoint = initialCheckpoint(batch)
        var firstOrdinal = 0L
        while (firstOrdinal < count) {
            val windowCount = minOf(CHECKPOINT_WINDOW_SIZE.toLong(), count - firstOrdinal).toInt()
            val authority = repository.committedOrdinalDigests(
                connection,
                batch.tenantId,
                batch.batchId,
                firstOrdinal,
                windowCount,
            )
            if (authority.size != windowCount || authority.withIndex().any { (index, item) ->
                    item.sourceOrdinal != firstOrdinal + index
                }
            ) {
                fail(BatchCommandFailure.ACTIVATION_INCOMPLETE)
            }
            authority.forEach { item ->
                checkpoint = advanceCheckpoint(checkpoint, item.sourceOrdinal, item.stableDedupDigest)
            }
            firstOrdinal += windowCount
        }
        return checkpoint
    }

    private fun initialCheckpoint(batch: BatchRecord): DigestValue {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FINAL_CHECKPOINT_DOMAIN.toByteArray(UTF_8))
        digest.update(batch.provenanceDigest.copyBytes())
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(batch.expectedCount).array())
        return DigestValue.of(digest.digest())
    }

    private fun advanceCheckpoint(previous: DigestValue, ordinal: Long, stableDigest: DigestValue): DigestValue {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CHECKPOINT_STEP_DOMAIN.toByteArray(UTF_8))
        digest.update(previous.copyBytes())
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(ordinal).array())
        digest.update(stableDigest.copyBytes())
        return DigestValue.of(digest.digest())
    }

    private fun <T> executeIdempotent(
        tenantId: String,
        rawKey: String,
        operation: String,
        fingerprint: CommandFingerprint,
        effectId: UUID,
        status: Int,
        outcome: String,
        lane: MutationLane,
        failureMapper: (RuntimeException) -> RuntimeException = { it },
        terminalEffect: (Connection, BatchCommandException) -> Unit = { _, _ -> },
        createRecovery: ((Connection) -> T?)? = null,
        effect: (Connection) -> T,
    ): MutationResult<T> {
        requireIdempotencyKey(rawKey)
        val scope = CommandScope(tenantId, operation)
        return when (val decision = transaction(lane) { idempotency.acquire(scope, rawKey, fingerprint) }) {
            is IdempotencyDecision.Execute -> executeOwned(
                decision.owner,
                effectId,
                status,
                outcome,
                lane,
                failureMapper,
                terminalEffect,
                createRecovery,
                effect,
            )
            is IdempotencyDecision.Replay -> MutationResult.Replay(decision.descriptor)
            is IdempotencyDecision.Expired -> MutationResult.Expired(decision.effectId, decision.terminalCode)
            is IdempotencyDecision.InProgress -> fail(BatchCommandFailure.COMMAND_IN_PROGRESS)
            IdempotencyDecision.FingerprintConflict -> fail(BatchCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT)
        }
    }

    private fun <T> executeOwned(
        owner: IdempotencyOwner,
        effectId: UUID,
        status: Int,
        outcome: String,
        lane: MutationLane,
        failureMapper: (RuntimeException) -> RuntimeException,
        terminalEffect: (Connection, BatchCommandException) -> Unit,
        createRecovery: ((Connection) -> T?)?,
        effect: (Connection) -> T,
    ): MutationResult<T> = try {
        val applied = transaction(lane) {
            idempotency.lockOwnerForExecution(owner)
            val value = effect(currentConnection())
            idempotency.finalize(
                owner,
                SafeResponseDescriptor.success(status, outcome, effectId, aggregateRevision(value as Any)),
                EffectReference.effect(effectId),
            )
            value
        }
        MutationResult.Applied(applied)
    } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
        val mapped = failureMapper(failure)
        if (mapped is BatchCommandException && mapped.reason == BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT && createRecovery != null) {
            recoverCreate(owner, effectId, status, outcome, lane, createRecovery)?.let { return MutationResult.Applied(it) }
        }
        when {
            mapped is BatchCommandException && mapped.reason.isTerminal() -> finalizeTerminal(owner, lane, mapped, terminalEffect)
            mapped.isRetryableMutationFailure() -> releaseRetryable(owner, lane)
        }
        throw mapped
    }

    private fun <T> recoverCreate(
        owner: IdempotencyOwner,
        effectId: UUID,
        status: Int,
        outcome: String,
        lane: MutationLane,
        recovery: (Connection) -> T?,
    ): T? = transaction(lane) {
        idempotency.lockOwnerForExecution(owner)
        val recovered = recovery(currentConnection()) ?: return@transaction null
        idempotency.finalize(
            owner,
            SafeResponseDescriptor.success(status, outcome, effectId, aggregateRevision(recovered)),
            EffectReference.effect(effectId),
        )
        recovered
    }

    private fun aggregateRevision(value: Any): Long = when (value) {
        is CampaignSnapshot -> value.revision
        is BatchSnapshot -> value.revision
        else -> error("idempotent mutation result requires an aggregate revision")
    }

    private fun finalizeTerminal(
        owner: IdempotencyOwner,
        lane: MutationLane,
        failure: BatchCommandException,
        terminalEffect: (Connection, BatchCommandException) -> Unit,
    ) {
        transaction(lane) {
            idempotency.lockOwnerForExecution(owner)
            terminalEffect(currentConnection(), failure)
            idempotency.finalize(
                owner,
                SafeResponseDescriptor.terminal(HTTP_CONFLICT, VoucherPoolErrorCode.BATCH_FAILED_TERMINAL),
                EffectReference.terminal(VoucherPoolErrorCode.BATCH_FAILED_TERMINAL),
            )
        }
    }

    private fun releaseRetryable(owner: IdempotencyOwner, lane: MutationLane) {
        releaseRetryableOwner {
            transaction(lane) { idempotency.releaseRetryable(owner) }
        }
    }

    private fun <T> transaction(lane: MutationLane, block: () -> T): T = when (lane) {
        MutationLane.OPERATOR -> executor.operatorTransaction(block)
        MutationLane.WORKER -> executor.workerTransaction(block)
    }

    private fun markTerminalBatchFailure(
        connection: Connection,
        tenantId: String,
        campaignId: UUID,
        batchId: UUID,
        failure: BatchCommandException,
        expectedManifest: DigestValue,
        expectedFirstOrdinal: Long,
        missingBatchFactory: ((CampaignRecord) -> BatchRecord)? = null,
    ) {
        if (failure.reason !in setOf(BatchCommandFailure.DUPLICATE_CODE, BatchCommandFailure.VALIDATION_REJECTED)) return
        val campaign = repository.lockCampaignForShare(connection, tenantId, campaignId)
        val requestedBatch = missingBatchFactory?.invoke(campaign)
        val existingBatch = repository.lockBatchForUpdate(connection, tenantId, batchId)
        val lockedBatch = when {
            existingBatch == null -> requestedBatch?.let { repository.createBatch(connection, it) }
            !matchesTerminalAuthority(
                connection,
                existingBatch,
                campaignId,
                expectedManifest,
                expectedFirstOrdinal,
                requestedBatch,
            ) -> return
            else -> existingBatch
        }
        lockedBatch?.let { batch ->
            if (batch.state == BatchState.STAGING) {
                val rejected = failure as? PreparedChunkRejectedException
                check(rejected == null || rejected.nextSourceOrdinal == batch.nextSourceOrdinal + rejected.rejectedCount)
                repository.markBatchTerminalFailure(
                    connection,
                    batch,
                    rejected?.rejectedCount ?: 0L,
                    rejected?.evidenceCode ?: "DUPLICATE_CODE",
                )
            }
        }
    }

    private fun matchesTerminalAuthority(
        connection: Connection,
        existingBatch: BatchRecord,
        campaignId: UUID,
        expectedManifest: DigestValue,
        expectedFirstOrdinal: Long,
        requestedBatch: BatchRecord?,
    ): Boolean {
        if (existingBatch.campaignId != campaignId) return false
        if (!existingBatch.provenanceDigest.secureEquals(expectedManifest)) return false
        if (existingBatch.nextSourceOrdinal != expectedFirstOrdinal) return false
        if (requestedBatch == null) return true
        if (!existingBatch.sameCreateAuthority(requestedBatch) || !existingBatch.hasNoProgress()) return false
        return repository.batchOrdinalCoverage(
            connection,
            existingBatch.tenantId,
            existingBatch.batchId,
        ).entryCount == 0L
    }

    private fun classifyIntegrityFailure(failure: RuntimeException): RuntimeException {
        if (failure.sqlState() != POSTGRES_UNIQUE_VIOLATION) return failure
        return when (failure.postgresConstraintName()) {
            in STABLE_DEDUP_CONSTRAINTS -> BatchCommandException(BatchCommandFailure.DUPLICATE_CODE)
            in RETRYABLE_COLLISION_CONSTRAINTS -> BatchCommandException(BatchCommandFailure.RETRYABLE_INTEGRITY_COLLISION)
            in CREATE_CONSTRAINTS -> BatchCommandException(BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT)
            in ORDINAL_CONSTRAINTS -> BatchCommandException(BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT)
            else -> failure
        }
    }

    private fun currentConnection(): Connection = checkNotNull(TransactionManager.currentOrNull()) {
        "campaign batch commands require an active VoucherPoolJdbcExecutor transaction"
    }.connection.connection as Connection

    private fun requireBatchScope(batch: BatchRecord, campaignId: UUID) {
        if (batch.campaignId != campaignId) fail(BatchCommandFailure.SCOPE_NOT_FOUND)
    }

    private fun requireRevision(actual: Long, expected: Long) {
        if (actual != expected) fail(BatchCommandFailure.STALE_REVISION)
    }

    private fun CampaignRecord.snapshot() = CampaignSnapshot(tenantId, campaignId, state, policyVersion, revision)

    private fun BatchRecord.snapshot() = BatchSnapshot(
        tenantId,
        batchId,
        campaignId,
        state,
        nextSourceOrdinal,
        expectedCount,
        acceptedCount,
        rejectedCount,
        checkpointDigest,
        lastFailureCode,
        revision,
    )

    private fun requestedCampaign(command: CreateCampaignCommand) = CampaignRecord(
        tenantId = command.tenantId,
        campaignId = command.campaignId,
        state = CampaignState.DRAFT,
        startsAt = command.startsAt,
        endsAt = command.endsAt,
        perUserLimit = command.policy.perUserLimit,
        reservationTtlSeconds = command.policy.reservationTtl.inWholeSeconds,
        allocationTtlSeconds = command.policy.allocationTtl.inWholeSeconds,
        replacementAllowance = command.policy.replacementAllowance,
        userIdentityKeyVersion = digests.currentUserIdentityKeyVersion,
        policyVersion = 1,
        revision = 0,
    )

    private fun requestedBatch(command: CreateImportBatchCommand, existingPolicyVersion: Long) = BatchRecord(
        tenantId = command.tenantId,
        batchId = command.batchId,
        campaignId = command.campaignId,
        state = BatchState.STAGING,
        sourceKind = command.sourceKind.name,
        provenanceDigest = command.manifestDigest,
        requestFingerprint = command.requestFingerprint,
        policyVersion = existingPolicyVersion,
        activatesAt = command.activatesAt,
        expiresAt = command.expiresAt,
        expectedCount = command.expectedCount,
        revision = 0,
    )

    private fun CreateCampaignCommand.fingerprint(): CommandFingerprint = VoucherPoolFingerprint.command(
        OP_CREATE_CAMPAIGN,
        mapOf(
            "campaignId" to campaignId.toString(),
            "startsAt" to startsAt.toString(),
            "endsAt" to endsAt.toString(),
            "perUserLimit" to policy.perUserLimit.toString(),
            "reservationTtlSeconds" to policy.reservationTtl.inWholeSeconds.toString(),
            "allocationTtlSeconds" to policy.allocationTtl.inWholeSeconds.toString(),
            "replacementAllowance" to policy.replacementAllowance.toString(),
        ),
    )

    private fun UpdateCampaignPolicyCommand.fingerprint(): CommandFingerprint = VoucherPoolFingerprint.command(
        OP_UPDATE_CAMPAIGN_POLICY,
        mapOf(
            "campaignId" to campaignId.toString(),
            "expectedRevision" to expectedRevision.toString(),
            "perUserLimit" to policy.perUserLimit.toString(),
            "reservationTtlSeconds" to policy.reservationTtl.inWholeSeconds.toString(),
            "allocationTtlSeconds" to policy.allocationTtl.inWholeSeconds.toString(),
            "replacementAllowance" to policy.replacementAllowance.toString(),
        ),
    )

    private fun CampaignRevisionCommand.fingerprint(operation: String): CommandFingerprint =
        VoucherPoolFingerprint.command(
            operation,
            mapOf("campaignId" to campaignId.toString(), "expectedRevision" to expectedRevision.toString()),
        )

    private fun CreateImportBatchCommand.fingerprint(prepared: PreparedChunk): CommandFingerprint =
        VoucherPoolFingerprint.command(
            OP_CREATE_BATCH,
            mapOf(
                "batchId" to batchId.toString(),
                "campaignId" to campaignId.toString(),
                "sourceKind" to sourceKind.name,
                "manifestDigest" to manifestDigest.copyBytes().toHex(),
                "requestFingerprint" to requestFingerprint.copyBytes().toHex(),
                "expectedCount" to expectedCount.toString(),
                "activatesAt" to activatesAt.toString(),
                "expiresAt" to expiresAt?.toString(),
                "initialChunk" to prepared.requestDigest(),
            ),
        )

    private fun ImportChunkCommand.fingerprint(prepared: PreparedChunk): CommandFingerprint = VoucherPoolFingerprint.command(
        OP_IMPORT_CHUNK,
        mapOf(
            "batchId" to batchId.toString(),
            "campaignId" to campaignId.toString(),
            "firstOrdinal" to firstOrdinal.toString(),
            "manifestDigest" to manifestDigest.copyBytes().toHex(),
            "chunk" to prepared.requestDigest(),
            "expectedRevision" to expectedRevision.toString(),
        ),
    )

    private fun GenerateChunkCommand.fingerprint(): CommandFingerprint = VoucherPoolFingerprint.command(
        OP_GENERATE_CHUNK,
        mapOf(
            "batchId" to batchId.toString(),
            "campaignId" to campaignId.toString(),
            "firstOrdinal" to firstOrdinal.toString(),
            "manifestDigest" to manifestDigest.copyBytes().toHex(),
            "count" to count.toString(),
            "expectedRevision" to expectedRevision.toString(),
        ),
    )

    private fun BatchRevisionCommand.fingerprint(operation: String = OP_ACTIVATE_BATCH): CommandFingerprint =
        VoucherPoolFingerprint.command(
        operation,
        mapOf(
            "batchId" to batchId.toString(),
            "campaignId" to campaignId.toString(),
            "expectedRevision" to expectedRevision.toString(),
        ),
    )

    private fun PreparedChunk.requestDigest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CHUNK_FINGERPRINT_DOMAIN.toByteArray(UTF_8))
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(firstOrdinal).array())
        entries.forEach {
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(it.sourceOrdinal).array())
            digest.update(it.stableDedupDigest.copyBytes())
        }
        rejections.forEach {
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(it.sourceOrdinal).array())
            digest.update(it.payloadDigest.copyBytes())
        }
        return digest.digest().toHex()
    }

    private data class PreparedChunk(
        val firstOrdinal: Long,
        val totalCount: Int,
        val entries: List<PreparedVoucherEntryRecord>,
        val rejections: List<RejectedVoucherEntryRecord>,
    )

    private enum class MutationLane { OPERATOR, WORKER }
}

private fun RejectedVoucherEntryRecord.evidenceCode(): String =
    "$reasonCode@$sourceOrdinal:${payloadDigest.copyBytes().take(EVIDENCE_DIGEST_BYTES).toByteArray().toHex()}"
        .take(MAX_FAILURE_CODE_LENGTH)

private fun Throwable.sqlState(): String? {
    val pending = ArrayDeque<Throwable>().apply { add(this@sqlState) }
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    repeat(MAX_EXCEPTION_CHAIN) {
        val candidate = pending.pollFirst() ?: return null
        if (!visited.add(candidate)) return@repeat
        if (candidate is SQLException && candidate.sqlState != null) return candidate.sqlState
        candidate.cause?.let(pending::addLast)
        if (candidate is SQLException) candidate.nextException?.let(pending::addLast)
    }
    return null
}

internal fun Throwable.postgresConstraintName(): String? {
    val pending = ArrayDeque<Throwable>().apply { add(this@postgresConstraintName) }
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    repeat(MAX_EXCEPTION_CHAIN) {
        val candidate = pending.pollFirst() ?: return null
        if (!visited.add(candidate)) return@repeat
        if (candidate is PSQLException) {
            val metadata: ServerErrorMessage? = candidate.serverErrorMessage
            val constraintName: String? = metadata?.constraint
            if (constraintName != null) return constraintName
        }
        candidate.cause?.let(pending::addLast)
        if (candidate is SQLException) candidate.nextException?.let(pending::addLast)
    }
    return null
}

private fun Throwable.isRetryableMutationFailure(): Boolean =
    (this is BatchCommandException && reason in RELEASE_COMMAND_FAILURES) ||
        sqlState() in RETRYABLE_SQL_STATES ||
        this::class.simpleName in RETRYABLE_FAILURE_TYPES

private fun BatchCommandFailure.isTerminal(): Boolean = this in TERMINAL_COMMAND_FAILURES

private fun CampaignRecord.sameCreateAuthority(other: CampaignRecord): Boolean =
    tenantId == other.tenantId && campaignId == other.campaignId && state == CampaignState.DRAFT &&
        startsAt.sameDatabaseInstant(other.startsAt) && endsAt.sameDatabaseInstant(other.endsAt) &&
        perUserLimit == other.perUserLimit &&
        reservationTtlSeconds == other.reservationTtlSeconds && allocationTtlSeconds == other.allocationTtlSeconds &&
        replacementAllowance == other.replacementAllowance

private fun BatchRecord.sameCreateAuthority(other: BatchRecord): Boolean =
    tenantId == other.tenantId && batchId == other.batchId && campaignId == other.campaignId &&
        sourceKind == other.sourceKind && provenanceDigest.secureEquals(other.provenanceDigest) &&
        requestFingerprint.secureEquals(other.requestFingerprint) && policyVersion == other.policyVersion &&
        activatesAt.sameDatabaseInstant(other.activatesAt) && expiresAt.sameDatabaseInstant(other.expiresAt) &&
        expectedCount == other.expectedCount

private fun BatchRecord.hasNoProgress(): Boolean =
    acceptedCount == 0L && rejectedCount == 0L && checkpointDigest == null && lastFailureCode == null

private fun Instant?.sameDatabaseInstant(other: Instant?): Boolean =
    this?.toPostgresInstant() == other?.toPostgresInstant()

private fun Instant.toPostgresInstant(): Instant {
    val roundedMicros = (nano.toLong() + NANOS_PER_MICRO / 2) / NANOS_PER_MICRO
    val normalizedSeconds = epochSecond + roundedMicros / MICROS_PER_SECOND
    val microsOfSecond = roundedMicros % MICROS_PER_SECOND
    return Instant.ofEpochSecond(normalizedSeconds, microsOfSecond * NANOS_PER_MICRO)
}

private fun DigestValue.secureEquals(other: DigestValue): Boolean =
    MessageDigest.isEqual(copyBytes(), other.copyBytes())

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun validateChunkPayload(codes: List<String>, allowEmpty: Boolean = false) {
    if (!allowEmpty) require(codes.isNotEmpty()) { "chunk must not be empty" }
    require(codes.size <= MAX_CHUNK_ENTRIES) { "chunk must contain at most $MAX_CHUNK_ENTRIES entries" }
    val payloadBytes = codes.sumOf { it.toByteArray(UTF_8).size.toLong() }
    require(payloadBytes <= MAX_JSON_PAYLOAD_BYTES) { "chunk payload exceeds $MAX_JSON_PAYLOAD_BYTES bytes" }
}

private fun requireTenant(tenantId: String) {
    require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH) { "tenantId is invalid" }
}

private fun requireIdempotencyKey(rawKey: String) {
    require(rawKey.length in MIN_IDEMPOTENCY_KEY_LENGTH..MAX_IDEMPOTENCY_KEY_LENGTH) { "idempotency key is invalid" }
    require(rawKey.none(Char::isISOControl)) { "idempotency key is invalid" }
}

private fun fail(reason: BatchCommandFailure): Nothing = throw BatchCommandException(reason)

private const val MAX_TENANT_LENGTH = 64
private const val MIN_IDEMPOTENCY_KEY_LENGTH = 8
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
private const val MAX_CHUNK_ENTRIES = 500
private const val MAX_BATCH_ENTRIES = 10_000L
private const val NANOS_PER_MICRO = 1_000L
private const val MICROS_PER_SECOND = 1_000_000L
private const val MAX_JSON_PAYLOAD_BYTES = 4L * 1_024 * 1_024
private const val GENERATED_ENTROPY_BYTES = 16
private const val EVIDENCE_DIGEST_BYTES = 8
private const val MAX_FAILURE_CODE_LENGTH = 64
private const val MAX_EXCEPTION_CHAIN = 32
private const val POSTGRES_UNIQUE_VIOLATION = "23505"
private const val FINAL_CHECKPOINT_DOMAIN = "voucher-pool-batch-final-checkpoint-v1"
private const val CHECKPOINT_STEP_DOMAIN = "voucher-pool-batch-checkpoint-step-v1"
private const val CHECKPOINT_WINDOW_SIZE = 500
private const val CHUNK_FINGERPRINT_DOMAIN = "voucher-pool-batch-chunk-fingerprint-v1"
private const val INVALID_CODE_AUDIT_OPERATION = "voucher-batch-invalid-code"
private const val HTTP_OK = 200
private const val HTTP_CREATED = 201
private const val HTTP_CONFLICT = 409
private const val OP_CREATE_CAMPAIGN = "campaign-create"
private const val OP_UPDATE_CAMPAIGN_POLICY = "campaign-policy-update"
private const val OP_ACTIVATE_CAMPAIGN = "campaign-activate"
private const val OP_PAUSE_CAMPAIGN = "campaign-pause"
private const val OP_RESUME_CAMPAIGN = "campaign-resume"
private const val OP_CREATE_BATCH = "batch-create"
private const val OP_IMPORT_CHUNK = "batch-import-chunk"
private const val OP_GENERATE_CHUNK = "batch-generate-chunk"
private const val OP_ACTIVATE_BATCH = "batch-activate"
private const val OP_PAUSE_BATCH = "batch-pause"
private const val OP_RESUME_BATCH = "batch-resume"
private val STABLE_DEDUP_CONSTRAINTS = setOf(
    "pk_voucher_pool_code_dedup",
    "uq_voucher_pool_dedup",
    "uq_voucher_pool_entry_stable_dedup",
)
private val RETRYABLE_COLLISION_CONSTRAINTS = setOf(
    "pk_voucher_pool_entries",
    "uq_voucher_pool_entry_identity",
    "uq_voucher_pool_entry_code_nonce",
    "uq_voucher_pool_entry_wrap_nonce",
)
private val CREATE_CONSTRAINTS = setOf(
    "pk_voucher_pool_campaigns",
    "pk_voucher_pool_batches",
    "uq_voucher_pool_batch_identity",
)
private val ORDINAL_CONSTRAINTS = setOf("uq_voucher_pool_entry_batch_ordinal")
private val RETRYABLE_SQL_STATES = setOf("40001", "40P01", "55P03", "57014")
private val RETRYABLE_FAILURE_TYPES = setOf("PoolBusyException", "VoucherPoolJdbcTimeoutException")
private val TERMINAL_COMMAND_FAILURES = setOf(
    BatchCommandFailure.DUPLICATE_CODE,
    BatchCommandFailure.CHUNK_FINGERPRINT_CONFLICT,
    BatchCommandFailure.CREATE_FINGERPRINT_CONFLICT,
    BatchCommandFailure.VALIDATION_REJECTED,
)
private val RELEASE_COMMAND_FAILURES = setOf(
    BatchCommandFailure.SCOPE_NOT_FOUND,
    BatchCommandFailure.STALE_REVISION,
    BatchCommandFailure.INVALID_STATE,
    BatchCommandFailure.ORDINAL_GAP,
    BatchCommandFailure.ACTIVATION_INCOMPLETE,
    BatchCommandFailure.RETRYABLE_INTEGRITY_COLLISION,
)
