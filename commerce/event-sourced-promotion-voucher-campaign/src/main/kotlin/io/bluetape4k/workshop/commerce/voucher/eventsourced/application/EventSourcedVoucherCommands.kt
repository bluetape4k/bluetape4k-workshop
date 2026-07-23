package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.codec.Base58
import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.support.requireEquals
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignState
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherCodeKeyVersions
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptScope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalDescriptor
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalKeyVersions
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRead
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.HmacPurpose
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentity
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.CAMPAIGN_STREAM_TYPE
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.CampaignEventCodec
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.VOUCHER_STREAM_TYPE
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.VoucherEventCodec
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import tools.jackson.databind.json.JsonMapper

private const val CAMPAIGN_SUBJECT_STREAM_TYPE = "campaign-subject"
private const val ALLOCATION_STATUS = 201
private const val CONFLICT_STATUS = 409
private const val NOT_FOUND_STATUS = 404
private const val CODE_PAYLOAD_LENGTH = 22
private const val CODE_DIGEST_BYTES = 16

internal data class AllocateVoucherCommandInput(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
    val campaignId: UUID,
    val userRef: String,
)

internal sealed interface VoucherCommandExecution {
    data class Completed(
        val descriptor: TerminalDescriptor,
        val replayed: Boolean,
    ) : VoucherCommandExecution

    data object InProgress : VoucherCommandExecution

    data object FingerprintConflict : VoucherCommandExecution

    data object KeyUnavailable : VoucherCommandExecution
}

internal interface EventSourcedVoucherCommands {
    fun allocate(input: AllocateVoucherCommandInput): VoucherCommandExecution

    fun voucherCode(
        tenant: String,
        campaignId: UUID,
        allocationId: UUID,
        keyVersion: Int,
    ): String

    fun voucher(
        tenant: String,
        voucherId: UUID,
    ): VoucherAggregate?
}

internal class DefaultEventSourcedVoucherCommands(
    private val commands: EventSourcedCommandService,
    private val events: EventStorePort,
    private val identities: SubjectIdentityService,
    private val keyRing: EventSourcedHmacKeyRing,
    private val clock: Clock = Clock.systemUTC(),
) : EventSourcedVoucherCommands {
    private val mapper = jsonMapper { }
    private val campaignEvents = CampaignEventCodec()
    private val voucherEvents = VoucherEventCodec()

    override fun allocate(input: AllocateVoucherCommandInput): VoucherCommandExecution {
        val command = input.validated()
        val now = clock.instant()
        val tenantId = TenantId(command.tenant)
        val subject = identities.resolve(tenantId, "voucher-subject", command.userRef)
        val principalDigest =
            keyRing.digest(HmacPurpose.PRINCIPAL_SCOPE, tenantId, "voucher-allocate", command.principal)
        val idempotencyDigest =
            keyRing.digest(
                HmacPurpose.IDEMPOTENCY_KEY,
                tenantId,
                "voucher-allocate:${command.campaignId}:${principalDigest.value}",
                command.idempotencyKey,
            )
        val scope =
            ReceiptScope(
                tenantId = tenantId,
                principalDigest = ReceiptDigest.of(principalDigest.value),
                operation = "ALLOCATE",
                resourceId = command.campaignId.toString(),
                keyDigest = ReceiptDigest.of(idempotencyDigest.value),
            )
        return commands.execute(
            EventSourcedCommand(
                scope = scope,
                fingerprint = ReceiptDigest.sha256("voucher-allocate-v1\u0000${command.userRef}"),
                acquiredAt = now,
                decideAfterRehydrate = {
                    decideAllocation(command, tenantId, subject, principalDigest.keyVersion, now)
                },
            ),
        ).toVoucherExecution()
    }

    override fun voucherCode(
        tenant: String,
        campaignId: UUID,
        allocationId: UUID,
        keyVersion: Int,
    ): String {
        val tenantId = TenantId(tenant.validCommandIdentity("tenant"))
        val digest =
            keyRing.digestWithVersion(
                keyVersion = keyVersion,
                purpose = HmacPurpose.VOUCHER_CODE,
                tenantId = tenantId,
                domain = "voucher-code:$campaignId",
                value = allocationId.toString(),
            )
        val payload =
            Base58
                .encode(HexFormat.of().parseHex(digest.value).copyOf(CODE_DIGEST_BYTES))
                .padStart(CODE_PAYLOAD_LENGTH, '1')
        return "V$keyVersion-${payload.takeLast(CODE_PAYLOAD_LENGTH)}"
    }

    override fun voucher(
        tenant: String,
        voucherId: UUID,
    ): VoucherAggregate? {
        val tenantId = TenantId(tenant.validCommandIdentity("tenant"))
        val page =
            events.load(
                EventStoreRead(
                    StreamKey(tenantId, VOUCHER_STREAM_TYPE, voucherId),
                    afterVersion = 0,
                ),
            )
        return page.events
            .mapNotNull(voucherEvents::decode)
            .takeIf(List<VoucherEvent>::isNotEmpty)
            ?.let(VoucherAggregate::replay)
    }

    private fun decideAllocation(
        command: AllocateVoucherCommandInput,
        tenantId: TenantId,
        subject: SubjectIdentity,
        hmacKeyVersion: Int,
        now: Instant,
    ): EventSourcedCommandDecision {
        val campaignStream = StreamKey(tenantId, CAMPAIGN_STREAM_TYPE, command.campaignId)
        val campaignPage = events.load(EventStoreRead(campaignStream, afterVersion = 0))
        return when {
            campaignPage.committedHead == 0L ->
                rejected(ReceiptOutcome.CAMPAIGN_NOT_FOUND, NOT_FOUND_STATUS, hmacKeyVersion, now)

            else -> {
                val campaign = CampaignAggregate.replay(campaignPage.events.mapNotNull(campaignEvents::decode))
                val campaignRejection = campaign.rejectionAt(now)
                if (campaignRejection != null) {
                    rejected(campaignRejection, CONFLICT_STATUS, hmacKeyVersion, now)
                } else {
                    val subjectStream =
                        StreamKey(
                            tenantId,
                            CAMPAIGN_SUBJECT_STREAM_TYPE,
                            subjectStreamId(command.campaignId, subject.surrogate),
                        )
                    val subjectPage = events.load(EventStoreRead(subjectStream, afterVersion = 0))
                    if (subjectPage.committedHead >= campaign.perUserLimit) {
                        rejected(ReceiptOutcome.PER_USER_LIMIT_REACHED, CONFLICT_STATUS, hmacKeyVersion, now)
                    } else {
                        acceptedAllocation(
                            AllocationDecisionContext(
                                command = command,
                                campaign =
                                    CampaignAllocationAuthority(
                                        aggregate = campaign,
                                        stream = campaignStream,
                                        version = campaignPage.committedHead,
                                    ),
                                subject =
                                    SubjectAllocationAuthority(
                                        identity = subject,
                                        stream = subjectStream,
                                        version = subjectPage.committedHead,
                                    ),
                                hmacKeyVersion = hmacKeyVersion,
                                now = now,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun acceptedAllocation(
        context: AllocationDecisionContext,
    ): EventSourcedCommandDecision {
        val command = context.command
        val campaign = context.campaign.aggregate
        val subject = context.subject.identity
        val now = context.now
        val voucherId = Uuid.V7.nextUUID()
        val expiresAt = minOf(now.plusSeconds(campaign.redemptionTtlSeconds), checkNotNull(campaign.endsAt))
        val reserved = CampaignEvent.VoucherCapacityReserved(voucherId, campaign.policyVersion)
        val issued = issuedVoucher(command, subject, voucherId, context.hmacKeyVersion)
        val allocated = VoucherEvent.VoucherAllocated(campaign.policyVersion, expiresAt)
        return EventSourcedCommandDecision(
            appends =
                listOf(
                    ExpectedAppend(
                        context.campaign.stream,
                        context.campaign.version,
                        listOf(reserved.toAppend(subject, now)),
                    ),
                    ExpectedAppend(
                        StreamKey(TenantId(command.tenant), VOUCHER_STREAM_TYPE, voucherId),
                        0,
                        listOf(issued.toAppend(subject, now), allocated.toAppend(subject, now)),
                    ),
                    ExpectedAppend(
                        context.subject.stream,
                        context.subject.version,
                        listOf(
                            subjectAllocation(
                                mapper = mapper,
                                voucherId = voucherId,
                                campaignId = command.campaignId,
                                subject = subject,
                                now = now,
                            ),
                        ),
                    ),
                ),
            descriptor =
                TerminalDescriptor(
                    outcome = ReceiptOutcome.VOUCHER_ALLOCATED,
                    status = ALLOCATION_STATUS,
                    allocationId = voucherId,
                    keyVersions =
                        TerminalKeyVersions(
                            hmac = context.hmacKeyVersion,
                            generationKeyVersion = context.hmacKeyVersion,
                            verificationKeyVersion = context.hmacKeyVersion,
                        ),
                ).withObservedAt(now),
        )
    }

    private fun CampaignEvent.toAppend(
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend = campaignEvents.encode(this).toAppend(subject, now)

    private fun VoucherEvent.toAppend(
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend = voucherEvents.encode(this).toAppend(subject, now)

    private fun io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.SerializedCampaignEvent.toAppend(
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend = append(eventType, payload, subject, now)

    private fun io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.SerializedVoucherEvent.toAppend(
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend = append(eventType, payload, subject, now)

    private fun append(
        eventType: String,
        payload: EventPayload,
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend {
        val eventId = Uuid.V7.nextUUID()
        return EventToAppend(
            eventId = eventId,
            eventType = eventType,
            schemaVersion = 1,
            payload = payload,
            occurredAt = now,
            correlationId = eventId.toString(),
            actorSurrogate = subject.surrogate.toString(),
            actorHmacKeyVersion = subject.hmacKeyVersion,
        )
    }

}

private data class CampaignAllocationAuthority(
    val aggregate: CampaignAggregate,
    val stream: StreamKey,
    val version: Long,
)

private data class SubjectAllocationAuthority(
    val identity: SubjectIdentity,
    val stream: StreamKey,
    val version: Long,
)

private data class AllocationDecisionContext(
    val command: AllocateVoucherCommandInput,
    val campaign: CampaignAllocationAuthority,
    val subject: SubjectAllocationAuthority,
    val hmacKeyVersion: Int,
    val now: Instant,
)

private fun issuedVoucher(
    command: AllocateVoucherCommandInput,
    subject: SubjectIdentity,
    voucherId: UUID,
    hmacKeyVersion: Int,
): VoucherEvent.VoucherIssued =
    VoucherEvent.VoucherIssued(
        tenantId = TenantId(command.tenant),
        campaignId = command.campaignId,
        voucherId = voucherId,
        subjectId = subject.surrogate,
        codeKeyVersions =
            VoucherCodeKeyVersions(
                generation = hmacKeyVersion,
                verification = hmacKeyVersion,
            ),
    )

private fun rejected(
    outcome: ReceiptOutcome,
    status: Int,
    hmacKeyVersion: Int,
    now: Instant,
): EventSourcedCommandDecision =
    EventSourcedCommandDecision(
        appends = emptyList(),
        descriptor =
            TerminalDescriptor(
                outcome = outcome,
                status = status,
                keyVersions = TerminalKeyVersions(hmac = hmacKeyVersion),
            ).withObservedAt(now),
    )

private fun subjectAllocation(
    mapper: JsonMapper,
    voucherId: UUID,
    campaignId: UUID,
    subject: SubjectIdentity,
    now: Instant,
): EventToAppend {
    val eventId = Uuid.V7.nextUUID()
    return EventToAppend(
        eventId = eventId,
        eventType = "campaign-subject.voucher-allocated",
        schemaVersion = 1,
        payload =
            EventPayload(
                mapper.writeValueAsString(mapOf("campaignId" to campaignId, "voucherId" to voucherId)),
            ),
        occurredAt = now,
        correlationId = eventId.toString(),
        actorSurrogate = subject.surrogate.toString(),
        actorHmacKeyVersion = subject.hmacKeyVersion,
    )
}

private fun AllocateVoucherCommandInput.validated(): AllocateVoucherCommandInput {
    val validPrincipal = principal.validCommandIdentity("principal")
    val validUserRef =
        userRef
            .validCommandIdentity("userRef")
            .requireEquals(validPrincipal, "userRef")
    return copy(
        tenant = tenant.validCommandIdentity("tenant"),
        principal = validPrincipal,
        idempotencyKey = idempotencyKey.validIdempotencyKey(),
        userRef = validUserRef,
    )
}

private fun CampaignAggregate.rejectionAt(now: Instant): ReceiptOutcome? =
    when {
        state != CampaignState.ACTIVE -> ReceiptOutcome.CAMPAIGN_NOT_ACTIVE
        now.isBefore(checkNotNull(startsAt)) -> ReceiptOutcome.CAMPAIGN_NOT_STARTED
        !now.isBefore(checkNotNull(endsAt)) -> ReceiptOutcome.CAMPAIGN_ENDED
        remainingCapacity <= 0 -> ReceiptOutcome.CAPACITY_EXHAUSTED
        else -> null
    }

private fun subjectStreamId(
    campaignId: UUID,
    subjectId: UUID,
): UUID =
    UUID.nameUUIDFromBytes("$campaignId\u0000$subjectId".toByteArray(UTF_8))

internal fun CommandExecutionResult.toVoucherExecution(): VoucherCommandExecution =
    when (this) {
        is CommandExecutionResult.Executed -> VoucherCommandExecution.Completed(descriptor, replayed = false)
        is CommandExecutionResult.Replayed -> VoucherCommandExecution.Completed(descriptor, replayed = true)
        is CommandExecutionResult.InProgress -> VoucherCommandExecution.InProgress
        is CommandExecutionResult.FingerprintConflict -> VoucherCommandExecution.FingerprintConflict
        is CommandExecutionResult.KeyUnavailable -> VoucherCommandExecution.KeyUnavailable
    }
