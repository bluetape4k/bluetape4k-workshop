@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "TooManyFunctions")

package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.javatimes.requirePositiveDuration
import io.bluetape4k.javatimes.seconds
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.checkNotNull
import io.bluetape4k.support.toUtf8Bytes
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.BatchState
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignPolicy
import io.bluetape4k.workshop.commerce.voucherpool.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucherpool.domain.EntryState
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.CommandScope
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.EffectReference
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyDecision
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.IdempotencyOwner
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.SafeResponseDescriptor
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolFingerprint
import io.bluetape4k.workshop.commerce.voucherpool.idempotency.VoucherPoolIdempotencyRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRepository
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolAllocationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolBatchTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolCampaignTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolEntryTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolRevokePreviewGrantTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolReservationTable
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolWorkerClaimTable
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigest
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.bluetape4k.workshop.commerce.voucherpool.worker.JdbcVoucherPoolWorkerRepository
import io.bluetape4k.workshop.commerce.voucherpool.worker.WorkerKind
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import java.io.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*

internal enum class RevokeAggregateType {
    CAMPAIGN,
    BATCH
}

internal enum class RevocationCommandFailure {
    SCOPE_NOT_FOUND,
    STALE_REVISION,
    INVALID_STATE,
    INVALID_PREVIEW,
    COMMAND_IN_PROGRESS,
    IDEMPOTENCY_FINGERPRINT_CONFLICT,
    REPLAY_WINDOW_EXPIRED,
}

internal class RevocationCommandException(val reason: RevocationCommandFailure): IllegalStateException(reason.name)

internal data class RevokePreviewSnapshot(
    val aggregateType: RevokeAggregateType,
    val aggregateId: UUID,
    val revision: Long,
    val counts: Map<EntryState, Long>,
    val eligibleDepth: Long,
    val activeReservations: Long,
    val activeAllocations: Long,
    val alreadyTerminalCount: Long,
    val affectedCount: Long,
    val previewToken: String,
    val expiresAt: Instant,
    val observedAt: Instant,
): Serializable {

    override fun toString(): String =
        "RevokePreviewSnapshot(aggregateType=$aggregateType,aggregateId=$aggregateId,revision=$revision," +
                "counts=$counts,eligibleDepth=$eligibleDepth,activeReservations=$activeReservations," +
                "activeAllocations=$activeAllocations,alreadyTerminalCount=$alreadyTerminalCount," +
                "affectedCount=$affectedCount,previewToken=[REDACTED],expiresAt=$expiresAt,observedAt=$observedAt)"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class RevokeProgressSnapshot(
    val aggregateType: RevokeAggregateType,
    val aggregateId: UUID,
    val state: String,
    val revision: Long,
    val workerCount: Int,
    val observedAt: Instant,
): Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal class RevokeCommand(
    val tenantId: String,
    val aggregateType: RevokeAggregateType,
    val aggregateId: UUID,
    val confirmedAggregateId: UUID,
    val expectedRevision: Long,
    val previewToken: String,
    val idempotencyKey: String,
) {
    init {
        require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH)
        require(expectedRevision >= 0)
        require(previewToken.isNotBlank() && previewToken.length <= MAX_PREVIEW_TOKEN_LENGTH)
        require(idempotencyKey.isNotBlank() && idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH)
    }

    override fun toString(): String =
        "RevokeCommand(tenantId=$tenantId,aggregateType=$aggregateType,aggregateId=$aggregateId," +
                "confirmedAggregateId=$confirmedAggregateId,expectedRevision=$expectedRevision," +
                "previewToken=[REDACTED],idempotencyKey=[REDACTED])"
}

internal interface VoucherPoolRevocationService {

    fun preview(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        expectedRevision: Long,
    ): RevokePreviewSnapshot

    fun revoke(command: RevokeCommand): MutationResult<RevokeProgressSnapshot>

    fun progress(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): RevokeProgressSnapshot?
}

/** single-use signed preview grant를 발급하고, accepted revocation을 durable worker에 atomic하게 넘깁니다. */
@Suppress("SqlResolve")
internal class JdbcVoucherPoolRevocationService(
    private val executor: VoucherPoolJdbcExecutor,
    private val repository: VoucherPoolRepository,
    private val idempotency: VoucherPoolIdempotencyRepository,
    private val digests: VoucherDigestService,
    private val claims: JdbcVoucherPoolWorkerRepository,
    private val random: SecureRandom = SecureRandom(),
    private val previewLifetime: Duration = 5.seconds(),
): VoucherPoolRevocationService {

    companion object: KLogging()

    init {
        previewLifetime.requirePositiveDuration("previewLifetimeDuration")
    }

    override fun preview(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        expectedRevision: Long,
    ): RevokePreviewSnapshot {
        require(tenantId.isNotBlank() && tenantId.length <= MAX_TENANT_LENGTH)
        require(expectedRevision >= 0)

        return executor.operatorTransaction {
            val authority = lockAggregate(tenantId, aggregateType, aggregateId)

            requireRevision(authority.revision, expectedRevision)
            requireRevocable(aggregateType, authority.state)

            val impact = readImpact(tenantId, aggregateType, aggregateId, authority.revision)
            issueGrant(tenantId, impact)
        }
    }

    override fun revoke(command: RevokeCommand): MutationResult<RevokeProgressSnapshot> {
        val operation = command.aggregateType.operation()
        val scope = CommandScope(command.tenantId, operation)

        val decision = executor.operatorTransaction {
            idempotency.acquire(scope, command.idempotencyKey, command.fingerprint(operation))
        }
        return when (decision) {
            is IdempotencyDecision.Execute    -> executeOwned(decision.owner, command)
            is IdempotencyDecision.Replay     -> MutationResult.Replay(decision.descriptor)
            is IdempotencyDecision.Expired    -> MutationResult.Expired(decision.effectId, decision.terminalCode)
            is IdempotencyDecision.InProgress -> fail(RevocationCommandFailure.COMMAND_IN_PROGRESS)
            IdempotencyDecision.FingerprintConflict -> fail(RevocationCommandFailure.IDEMPOTENCY_FINGERPRINT_CONFLICT)
        }
    }

    override fun progress(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): RevokeProgressSnapshot? = executor.operatorTransaction {
        // state/revision은 Exposed로 읽고, observedAt만 PostgreSQL transaction snapshot을 사용합니다.
        val observedAt = repository.transactionTime()
        when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> VoucherPoolCampaignTable
                .select(VoucherPoolCampaignTable.state, VoucherPoolCampaignTable.revision)
                .where {
                    (VoucherPoolCampaignTable.tenantId eq tenantId) and
                            (VoucherPoolCampaignTable.campaignId eq aggregateId)
                }
                .singleOrNull()
                ?.let { row ->
                    RevokeProgressSnapshot(
                        aggregateType = aggregateType,
                        aggregateId = aggregateId,
                        state = row[VoucherPoolCampaignTable.state].name,
                        revision = row[VoucherPoolCampaignTable.revision],
                        workerCount = campaignWorkerCount(tenantId, aggregateId),
                        observedAt = observedAt,
                    )
                }

            RevokeAggregateType.BATCH -> VoucherPoolBatchTable
                .select(VoucherPoolBatchTable.state, VoucherPoolBatchTable.revision)
                .where {
                    (VoucherPoolBatchTable.tenantId eq tenantId) and
                            (VoucherPoolBatchTable.batchId eq aggregateId)
                }
                .singleOrNull()
                ?.let { row ->
                    RevokeProgressSnapshot(
                        aggregateType = aggregateType,
                        aggregateId = aggregateId,
                        state = row[VoucherPoolBatchTable.state].name,
                        revision = row[VoucherPoolBatchTable.revision],
                        workerCount = batchWorkerCount(tenantId, aggregateId),
                        observedAt = observedAt,
                    )
                }
        }
    }

    private fun executeOwned(
        owner: IdempotencyOwner,
        command: RevokeCommand,
    ): MutationResult<RevokeProgressSnapshot> = try {
        val progress = executor.operatorTransaction {
            idempotency.lockOwnerForExecution(owner)
            val authority = lockAggregate(command.tenantId, command.aggregateType, command.aggregateId)
            requireRevision(authority.revision, command.expectedRevision)
            if (command.confirmedAggregateId != command.aggregateId) fail(RevocationCommandFailure.INVALID_PREVIEW)
            val token = parseToken(command.previewToken)
            val grant = lockGrant(command.tenantId, token.grantId)
                ?: fail(RevocationCommandFailure.INVALID_PREVIEW)

            val impact = readImpact(
                tenantId = command.tenantId,
                aggregateType = command.aggregateType,
                aggregateId = command.aggregateId,
                revision = authority.revision,
            )
            validateGrant(command, token, grant, impact)
            requireRevocable(command.aggregateType, authority.state)
            consumeGrant(grant)
            val updated = transitionAndHandoff(command)
            idempotency.finalize(
                owner = owner,
                descriptor = SafeResponseDescriptor.success(
                    status = HTTP_ACCEPTED,
                    outcome = "REVOCATION_ACCEPTED",
                    effectId = command.aggregateId,
                    revision = updated.revision
                ),
                effect = EffectReference.effect(command.aggregateId),
            )
            updated
        }
        MutationResult.Applied(progress)
    } catch (@Suppress("TooGenericExceptionCaught") failure: RuntimeException) {
        releaseOwner(owner)
        throw failure
    }

    private fun releaseOwner(owner: IdempotencyOwner) {
        try {
            executor.operatorTransaction {
                idempotency.releaseRetryable(owner)
            }
        } catch (_: RuntimeException) {
            // 원래 command failure는 authoritative로 남고, ownership expiry는 안전한 fallback입니다.
        }
    }

    private fun transitionAndHandoff(
        command: RevokeCommand,
    ): RevokeProgressSnapshot {
        val now = repository.transactionTime()
        return when (command.aggregateType) {
            RevokeAggregateType.CAMPAIGN -> {
                val campaign = repository.lockCampaignForUpdate(
                    tenantId = command.tenantId,
                    campaignId = command.aggregateId
                ).checkNotNull("campaign")

                val updated = repository.updateCampaign(
                    campaign = campaign.copy(state = CampaignState.REVOKING),
                    expectedRevision = campaign.revision
                )
                val workerCount = campaignWorkerCount(command.tenantId, command.aggregateId)

                val terminal = if (workerCount == 0) {
                    repository.updateCampaign(
                        updated.copy(state = CampaignState.REVOKED),
                        updated.revision,
                    )
                } else {
                    claims.ensureClaimInTransaction(
                        command.tenantId,
                        WorkerKind.CAMPAIGN_REVOKE,
                        command.aggregateId,
                    )
                    updated
                }

                RevokeProgressSnapshot(
                    aggregateType = command.aggregateType,
                    aggregateId = command.aggregateId,
                    state = terminal.state.name,
                    revision = terminal.revision,
                    workerCount = workerCount,
                    observedAt = now,
                )
            }

            RevokeAggregateType.BATCH    -> {
                val batch = repository.lockBatchForUpdate(
                    command.tenantId,
                    command.aggregateId
                ).checkNotNull("batch")
                val updated = repository.updateBatchState(batch, BatchState.REVOKING)
                claims.ensureClaimInTransaction(
                    tenantId = command.tenantId,
                    kind = WorkerKind.BATCH_REVOKE,
                    scopeId = command.aggregateId,
                )
                RevokeProgressSnapshot(
                    command.aggregateType,
                    command.aggregateId,
                    updated.state.name,
                    updated.revision,
                    1,
                    now
                )
            }
        }
    }

    private fun issueGrant(
        tenantId: String,
        impact: ImpactSnapshot,
    ): RevokePreviewSnapshot {
        val grantId = Uuid.V7.nextId()
        // nonce는 서명 토큰의 cryptographic nonce이므로 SecureRandom 생성 경로를 유지한다.
        val nonce = ByteArray(TOKEN_NONCE_BYTES).also(random::nextBytes)
        val expiresAt = impact.observedAt.plus(previewLifetime)
        val material = tokenMaterial(grantId, nonce, impact, expiresAt)
        val signature = digests.audit(tenantId, TOKEN_OPERATION, material)
        val signatureBytes = signature.copyBytes()

        return try {
            val inserted = VoucherPoolRevokePreviewGrantTable.insert {
                it[VoucherPoolRevokePreviewGrantTable.tenantId] = tenantId
                it[VoucherPoolRevokePreviewGrantTable.grantId] = grantId
                it[VoucherPoolRevokePreviewGrantTable.aggregateType] = impact.aggregateType.name
                it[VoucherPoolRevokePreviewGrantTable.aggregateId] = impact.aggregateId
                it[VoucherPoolRevokePreviewGrantTable.aggregateRevision] = impact.revision
                it[VoucherPoolRevokePreviewGrantTable.impactDigest] = impact.impactDigest
                it[VoucherPoolRevokePreviewGrantTable.affectedCount] = impact.affectedCount
                it[VoucherPoolRevokePreviewGrantTable.signatureKeyVersion] = signature.keyVersion
                it[VoucherPoolRevokePreviewGrantTable.signatureDigest] = signatureBytes
                it[VoucherPoolRevokePreviewGrantTable.expiresAt] = expiresAt
            }
            check(inserted.insertedCount == 1)
            val token = encodeToken(grantId, nonce, signature)
            RevokePreviewSnapshot(
                aggregateType = impact.aggregateType,
                aggregateId = impact.aggregateId,
                revision = impact.revision,
                counts = impact.counts,
                eligibleDepth = impact.eligibleDepth,
                activeReservations = impact.activeReservations,
                activeAllocations = impact.activeAllocations,
                alreadyTerminalCount = impact.alreadyTerminalCount,
                affectedCount = impact.affectedCount,
                previewToken = token,
                expiresAt = expiresAt,
                observedAt = impact.observedAt,
            )
        } finally {
            nonce.fill(0)
            signatureBytes.fill(0)
        }
    }

    private fun validateGrant(
        command: RevokeCommand,
        token: ParsedToken,
        grant: RevokeGrant,
        impact: ImpactSnapshot,
    ) {
        val now = impact.observedAt
        val validGrant = sequenceOf(
            grant.aggregateType == command.aggregateType,
            grant.aggregateId == command.aggregateId,
            grant.aggregateRevision == command.expectedRevision,
            grant.consumedAt == null,
            grant.expiresAt > now,
            grant.affectedCount == impact.affectedCount,
            MessageDigest.isEqual(grant.impactDigest, impact.impactDigest),
            grant.signatureKeyVersion == token.keyVersion,
            MessageDigest.isEqual(grant.signatureDigest, token.signature),
        ).all { it }
        if (!validGrant) {
            fail(RevocationCommandFailure.INVALID_PREVIEW)
        }

        val expected = VoucherDigest.of(DigestPurpose.AUDIT, token.keyVersion, token.signature)
        val material = tokenMaterial(token.grantId, token.nonce, impact, grant.expiresAt)
        if (!digests.matchesAudit(command.tenantId, TOKEN_OPERATION, material, expected)) {
            fail(RevocationCommandFailure.INVALID_PREVIEW)
        }
    }

    private fun consumeGrant(grant: RevokeGrant) {
        val updated = VoucherPoolRevokePreviewGrantTable.update({
            (VoucherPoolRevokePreviewGrantTable.tenantId eq grant.tenantId) and
                    (VoucherPoolRevokePreviewGrantTable.grantId eq grant.grantId) and
                    VoucherPoolRevokePreviewGrantTable.consumedAt.isNull() and
                    (VoucherPoolRevokePreviewGrantTable.expiresAt greater CurrentTimestamp)
        }) {
            it[VoucherPoolRevokePreviewGrantTable.consumedAt] = CurrentTimestamp
        }
        if (updated != 1) fail(RevocationCommandFailure.INVALID_PREVIEW)
    }

    private fun lockGrant(tenantId: String, grantId: UUID): RevokeGrant? =
        VoucherPoolRevokePreviewGrantTable
            .selectAll()
            .where {
                (VoucherPoolRevokePreviewGrantTable.tenantId eq tenantId) and
                        (VoucherPoolRevokePreviewGrantTable.grantId eq grantId)
            }
            .forUpdate()
            .singleOrNull()
            ?.let { it.revokeGrant() }

    private fun lockAggregate(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): LockedAggregate = when (aggregateType) {
        RevokeAggregateType.CAMPAIGN -> {
            val campaign = repository.lockCampaignForUpdate(tenantId, aggregateId)
                ?: fail(RevocationCommandFailure.SCOPE_NOT_FOUND)
            LockedAggregate(campaign.state.name, campaign.revision)
        }

        RevokeAggregateType.BATCH ->
            lockBatchAggregate(tenantId, aggregateId)
    }

    private fun lockBatchAggregate(tenantId: String, batchId: UUID): LockedAggregate {
        val campaignId = VoucherPoolBatchTable
            .select(VoucherPoolBatchTable.campaignId)
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.batchId eq batchId)
            }
            .singleOrNull()
            ?.get(VoucherPoolBatchTable.campaignId)
            ?: fail(RevocationCommandFailure.SCOPE_NOT_FOUND)

        repository.lockCampaignForShare(tenantId, campaignId)
        val batch = repository.lockBatchForUpdate(tenantId, batchId)
            ?: fail(RevocationCommandFailure.SCOPE_NOT_FOUND)

        return LockedAggregate(batch.state.name, batch.revision)
    }

    private fun readImpact(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
        revision: Long,
    ): ImpactSnapshot {
        val counts = EntryState.entries.associateWith { 0L }.toMutableMap()
        val entryScope = when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> VoucherPoolEntryTable.campaignId eq aggregateId
            RevokeAggregateType.BATCH -> VoucherPoolEntryTable.batchId eq aggregateId
        }
        val entryCount = VoucherPoolEntryTable.entryId.count()
        VoucherPoolEntryTable
            .select(VoucherPoolEntryTable.state, entryCount)
            .where { (VoucherPoolEntryTable.tenantId eq tenantId) and entryScope }
            .groupBy(VoucherPoolEntryTable.state)
            .forEach { row -> counts[row[VoucherPoolEntryTable.state]] = row[entryCount] }

        val reservationScope = when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> VoucherPoolReservationTable.campaignId eq aggregateId
            RevokeAggregateType.BATCH -> VoucherPoolReservationTable.batchId eq aggregateId
        }
        val activeReservations = VoucherPoolReservationTable
            .selectAll()
            .where {
                (VoucherPoolReservationTable.tenantId eq tenantId) and
                        reservationScope and
                        (VoucherPoolReservationTable.state eq io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState.ACTIVE)
            }
            .count()
        val eligibleDepth = eligibleDepth(tenantId, aggregateType, aggregateId)
        val activeAllocations = counts.getValue(EntryState.ALLOCATED)
        val affectedCount = counts.getValue(EntryState.AVAILABLE) +
                counts.getValue(EntryState.RESERVED) + activeAllocations
        val terminalCount = counts.getValue(EntryState.REDEEMED) +
                counts.getValue(EntryState.RELEASED) +
                counts.getValue(EntryState.REVOKED) +
                counts.getValue(EntryState.EXPIRED)

        val observedAt = repository.transactionTime()

        val digest = impactDigest(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            revision = revision,
            counts = counts,
            eligibleDepth = eligibleDepth,
            activeReservations = activeReservations,
            activeAllocations = activeAllocations,
            terminalCount = terminalCount,
            affectedCount = affectedCount,
        )
        return ImpactSnapshot(
            aggregateType = aggregateType,
            aggregateId = aggregateId,
            revision = revision,
            counts = counts.toMap(),
            eligibleDepth = eligibleDepth,
            activeReservations = activeReservations,
            activeAllocations = activeAllocations,
            alreadyTerminalCount = terminalCount,
            affectedCount = affectedCount,
            impactDigest = digest,
            observedAt = observedAt,
        )
    }

    private fun eligibleDepth(
        tenantId: String,
        aggregateType: RevokeAggregateType,
        aggregateId: UUID,
    ): Long {
        val aggregateScope = when (aggregateType) {
            RevokeAggregateType.CAMPAIGN -> VoucherPoolEntryTable.campaignId eq aggregateId
            RevokeAggregateType.BATCH -> VoucherPoolEntryTable.batchId eq aggregateId
        }
        val joined = VoucherPoolEntryTable
            .innerJoin(
                VoucherPoolCampaignTable,
                { VoucherPoolEntryTable.tenantId },
                { VoucherPoolCampaignTable.tenantId },
                additionalConstraint = { VoucherPoolEntryTable.campaignId eq VoucherPoolCampaignTable.campaignId },
            )
            .innerJoin(
                VoucherPoolBatchTable,
                { VoucherPoolEntryTable.tenantId },
                { VoucherPoolBatchTable.tenantId },
                additionalConstraint = {
                    (VoucherPoolEntryTable.batchId eq VoucherPoolBatchTable.batchId) and
                            (VoucherPoolEntryTable.campaignId eq VoucherPoolBatchTable.campaignId)
                },
            )
        return joined
            .select(VoucherPoolEntryTable.entryId)
            .where {
                (VoucherPoolEntryTable.tenantId eq tenantId) and
                        aggregateScope and
                        (VoucherPoolEntryTable.state eq EntryState.AVAILABLE) and
                        VoucherPoolEntryTable.quarantinedAt.isNull() and
                        (VoucherPoolCampaignTable.state eq CampaignState.ACTIVE) and
                        (VoucherPoolCampaignTable.startsAt lessEq CurrentTimestamp) and
                        (VoucherPoolCampaignTable.endsAt greater CurrentTimestamp) and
                        (VoucherPoolBatchTable.state eq BatchState.ACTIVE) and
                        (VoucherPoolBatchTable.activatesAt lessEq CurrentTimestamp) and
                        (VoucherPoolBatchTable.expiresAt.isNull() or
                                (VoucherPoolBatchTable.expiresAt greater CurrentTimestamp))
            }
            .count()
    }

    private fun campaignWorkerCount(tenantId: String, campaignId: UUID): Int =
        VoucherPoolBatchTable
            .selectAll()
            .where {
                (VoucherPoolBatchTable.tenantId eq tenantId) and
                        (VoucherPoolBatchTable.campaignId eq campaignId) and
                        (VoucherPoolBatchTable.state notInList listOf(BatchState.REVOKED, BatchState.EXPIRED))
            }
            .count()
            .toInt()

    private fun batchWorkerCount(tenantId: String, batchId: UUID): Int =
        VoucherPoolWorkerClaimTable
            .selectAll()
            .where {
                (VoucherPoolWorkerClaimTable.tenantId eq tenantId) and
                        (VoucherPoolWorkerClaimTable.workerType eq WorkerKind.BATCH_REVOKE.name) and
                        (VoucherPoolWorkerClaimTable.scopeId eq batchId)
            }
            .count()
            .toInt()

    private fun requireRevision(actual: Long, expected: Long) {
        if (actual != expected) fail(RevocationCommandFailure.STALE_REVISION)
    }

    private fun requireRevocable(aggregateType: RevokeAggregateType, state: String) {
        val allowed = when (aggregateType) {
            RevokeAggregateType.CAMPAIGN ->
                CampaignPolicy.canTransition(
                    CampaignState.valueOf(state),
                    CampaignState.REVOKING
                )
            RevokeAggregateType.BATCH    ->
                BatchPolicy.canTransition(BatchState.valueOf(state), BatchState.REVOKING)
        }
        if (!allowed) fail(RevocationCommandFailure.INVALID_STATE)
    }

    private fun RevokeCommand.fingerprint(operation: String): CommandFingerprint =
        VoucherPoolFingerprint.command(
            operation = operation,
            fields = mapOf(
                "aggregateType" to aggregateType.name,
                "aggregateId" to aggregateId.toString(),
                "confirmedAggregateId" to confirmedAggregateId.toString(),
                "expectedRevision" to expectedRevision.toString(),
                "previewTokenDigest" to MessageDigest.getInstance("SHA-256").digest(previewToken.toUtf8Bytes()).toHex(),
            ),
        )

    private fun RevokeAggregateType.operation(): String = "${name.lowercase()}-revoke"

    private fun tokenMaterial(
        grantId: UUID,
        nonce: ByteArray,
        impact: ImpactSnapshot,
        expiresAt: Instant,
    ): String =
        listOf(
            grantId,
            Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
            impact.aggregateType,
            impact.aggregateId,
            impact.revision,
            Base64.getUrlEncoder().withoutPadding().encodeToString(impact.impactDigest),
            impact.affectedCount,
            expiresAt.epochSecond,
            expiresAt.nano,
        )
            .joinToString("|")

    private fun encodeToken(grantId: UUID, nonce: ByteArray, signature: VoucherDigest): String {
        val signatureBytes = signature.copyBytes()

        return try {
            listOf(
                TOKEN_VERSION,
                grantId,
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                signature.keyVersion,
                Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes),
            ).joinToString(".")
        } finally {
            signatureBytes.fill(0)
        }
    }

    private fun parseToken(raw: String): ParsedToken =
        try {
            val parts = raw.split('.')
            if (parts.size != TOKEN_PARTS || parts[0] != TOKEN_VERSION) fail(RevocationCommandFailure.INVALID_PREVIEW)
            val nonce = Base64.getUrlDecoder().decode(parts[2])
            val signature = Base64.getUrlDecoder().decode(parts[4])
            if (nonce.size != TOKEN_NONCE_BYTES || signature.size != TOKEN_SIGNATURE_BYTES) {
                fail(RevocationCommandFailure.INVALID_PREVIEW)
            }
            ParsedToken(UUID.fromString(parts[1]), nonce, parts[3].toInt(), signature)
        } catch (failure: RevocationCommandException) {
            throw failure
        } catch (_: RuntimeException) {
            fail(RevocationCommandFailure.INVALID_PREVIEW)
        }

    private data class LockedAggregate(
        val state: String,
        val revision: Long,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class ImpactSnapshot(
        val aggregateType: RevokeAggregateType,
        val aggregateId: UUID,
        val revision: Long,
        val counts: Map<EntryState, Long>,
        val eligibleDepth: Long,
        val activeReservations: Long,
        val activeAllocations: Long,
        val alreadyTerminalCount: Long,
        val affectedCount: Long,
        val impactDigest: ByteArray,
        val observedAt: Instant,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class ParsedToken(
        val grantId: UUID,
        val nonce: ByteArray,
        val keyVersion: Int,
        val signature: ByteArray,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private data class RevokeGrant(
        val tenantId: String,
        val grantId: UUID,
        val aggregateType: RevokeAggregateType,
        val aggregateId: UUID,
        val aggregateRevision: Long,
        val impactDigest: ByteArray,
        val affectedCount: Long,
        val signatureKeyVersion: Int,
        val signatureDigest: ByteArray,
        val expiresAt: Instant,
        val consumedAt: Instant?,
    ): Serializable {
        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    private fun ResultRow.revokeGrant(): RevokeGrant =
        RevokeGrant(
            tenantId = this[VoucherPoolRevokePreviewGrantTable.tenantId],
            grantId = this[VoucherPoolRevokePreviewGrantTable.grantId],
            aggregateType = RevokeAggregateType.valueOf(this[VoucherPoolRevokePreviewGrantTable.aggregateType]),
            aggregateId = this[VoucherPoolRevokePreviewGrantTable.aggregateId],
            aggregateRevision = this[VoucherPoolRevokePreviewGrantTable.aggregateRevision],
            impactDigest = this[VoucherPoolRevokePreviewGrantTable.impactDigest],
            affectedCount = this[VoucherPoolRevokePreviewGrantTable.affectedCount],
            signatureKeyVersion = this[VoucherPoolRevokePreviewGrantTable.signatureKeyVersion],
            signatureDigest = this[VoucherPoolRevokePreviewGrantTable.signatureDigest],
            expiresAt = this[VoucherPoolRevokePreviewGrantTable.expiresAt],
            consumedAt = this[VoucherPoolRevokePreviewGrantTable.consumedAt],
        )
}

private fun impactDigest(
    aggregateType: RevokeAggregateType,
    aggregateId: UUID,
    revision: Long,
    counts: Map<EntryState, Long>,
    eligibleDepth: Long,
    activeReservations: Long,
    activeAllocations: Long,
    terminalCount: Long,
    affectedCount: Long,
): ByteArray {
    val material = buildString {
        append(aggregateType).append('|').append(aggregateId).append('|').append(revision)
        EntryState.entries.forEach { state -> append('|').append(state).append('=').append(counts[state] ?: 0L) }
        append('|').append(eligibleDepth)
        append('|').append(activeReservations)
        append('|').append(activeAllocations)
        append('|').append(terminalCount)
        append('|').append(affectedCount)
    }
    return MessageDigest.getInstance("SHA-256").digest(material.toUtf8Bytes())
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

private fun fail(reason: RevocationCommandFailure): Nothing = throw RevocationCommandException(reason)

private const val HTTP_ACCEPTED = 202
private const val MAX_TENANT_LENGTH = 64
private const val MAX_PREVIEW_TOKEN_LENGTH = 512
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200
private const val TOKEN_OPERATION = "operator-revoke-preview-token-v1"
private const val TOKEN_VERSION = "v1"
private const val TOKEN_PARTS = 5
private const val TOKEN_NONCE_BYTES = 24
private const val TOKEN_SIGNATURE_BYTES = 32
