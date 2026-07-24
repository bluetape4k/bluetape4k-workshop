package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignCommands
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.DomainTransitionException
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptScope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalDescriptor
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalKeyVersions
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStorePort
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventStoreRead
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.HmacPurpose
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentity
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityService
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.CAMPAIGN_STREAM_TYPE
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.CampaignEventCodec
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val CAMPAIGN_CREATED_STATUS = 201
private const val CAMPAIGN_TRANSITION_STATUS = 200
private const val CAMPAIGN_CONFLICT_STATUS = 409

internal data class CreateCampaignCommandInput(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
    val campaignId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
)

internal data class ActivateCampaignCommandInput(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
    val campaignId: UUID,
    val expectedRevision: Long,
)

internal sealed interface CampaignCommandExecution {
    data class Completed(
        val descriptor: TerminalDescriptor,
        val replayed: Boolean,
    ) : CampaignCommandExecution

    data object InProgress : CampaignCommandExecution

    data object FingerprintConflict : CampaignCommandExecution

    data object KeyUnavailable : CampaignCommandExecution
}

private data class CampaignActivationContext(
    val stream: StreamKey,
    val expectedVersion: Long,
    val subject: SubjectIdentity,
    val hmacKeyVersion: Int,
    val now: Instant,
)

internal interface EventSourcedCampaignCommands {
    fun create(input: CreateCampaignCommandInput): CampaignCommandExecution

    fun activate(input: ActivateCampaignCommandInput): CampaignCommandExecution

    fun campaign(
        tenant: String,
        campaignId: UUID,
    ): CampaignAggregate?
}

/** Converts one validated campaign command into the generic receipt plus append orchestration boundary. */
internal class DefaultEventSourcedCampaignCommands(
    private val commands: EventSourcedCommandService,
    private val events: EventStorePort,
    private val identities: SubjectIdentityService,
    private val keyRing: EventSourcedHmacKeyRing,
    private val clock: Clock = Clock.systemUTC(),
) : EventSourcedCampaignCommands {
    private val mapper = jsonMapper { }
    private val campaignEvents = CampaignEventCodec()

    override fun create(input: CreateCampaignCommandInput): CampaignCommandExecution {
        val command = input.validated()
        val now = clock.instant()
        val tenantId = TenantId(command.tenant)
        val subject = identities.resolve(tenantId, "campaign-principal", command.principal)
        val principalDigest =
            keyRing.digest(
                purpose = HmacPurpose.PRINCIPAL_SCOPE,
                tenantId = tenantId,
                domain = "campaign-create",
                value = command.principal,
            )
        val idempotencyDigest =
            keyRing.digest(
                purpose = HmacPurpose.IDEMPOTENCY_KEY,
                tenantId = tenantId,
                domain = "campaign-create:${command.campaignId}:${principalDigest.value}",
                value = command.idempotencyKey,
            )
        val scope =
            ReceiptScope(
                tenantId = tenantId,
                principalDigest = ReceiptDigest.of(principalDigest.value),
                operation = "CAMPAIGN_CREATE",
                resourceId = command.campaignId.toString(),
                keyDigest = ReceiptDigest.of(idempotencyDigest.value),
            )
        val fingerprint = fingerprint(command)
        val created = command.toEvent(tenantId)
        return commands.execute(
            EventSourcedCommand(
                scope = scope,
                fingerprint = fingerprint,
                acquiredAt = now,
                decideAfterRehydrate = {
                    EventSourcedCommandDecision(
                        appends =
                            listOf(
                                ExpectedAppend(
                                    stream = StreamKey(tenantId, CAMPAIGN_STREAM_TYPE, command.campaignId),
                                    expectedVersion = 0,
                                    events = listOf(created.toAppend(subject, now)),
                                ),
                            ),
                        descriptor =
                            TerminalDescriptor(
                                outcome = ReceiptOutcome.CAMPAIGN_CREATED,
                                status = CAMPAIGN_CREATED_STATUS,
                                keyVersions = TerminalKeyVersions(hmac = principalDigest.keyVersion),
                            ).withObservedAt(now),
                    )
                },
            ),
        ).toCampaignExecution()
    }

    override fun activate(input: ActivateCampaignCommandInput): CampaignCommandExecution {
        val command = input.validated()
        val now = clock.instant()
        val tenantId = TenantId(command.tenant)
        val subject = identities.resolve(tenantId, "campaign-principal", command.principal)
        val principalDigest =
            keyRing.digest(
                purpose = HmacPurpose.PRINCIPAL_SCOPE,
                tenantId = tenantId,
                domain = "campaign-activate",
                value = command.principal,
            )
        val idempotencyDigest =
            keyRing.digest(
                purpose = HmacPurpose.IDEMPOTENCY_KEY,
                tenantId = tenantId,
                domain = "campaign-activate:${command.campaignId}:${principalDigest.value}",
                value = command.idempotencyKey,
            )
        val scope =
            ReceiptScope(
                tenantId = tenantId,
                principalDigest = ReceiptDigest.of(principalDigest.value),
                operation = "CAMPAIGN_ACTIVATE",
                resourceId = command.campaignId.toString(),
                keyDigest = ReceiptDigest.of(idempotencyDigest.value),
            )
        return commands.execute(
            EventSourcedCommand(
                scope = scope,
                fingerprint = ReceiptDigest.sha256("voucher-campaign-activate-v1\u0000${command.expectedRevision}"),
                acquiredAt = now,
                decideAfterRehydrate = {
                    decideActivation(command, tenantId, subject, principalDigest.keyVersion, now)
                },
            ),
        ).toCampaignExecution()
    }

    override fun campaign(
        tenant: String,
        campaignId: UUID,
    ): CampaignAggregate? {
        val tenantId = TenantId(tenant.requireNotBlank("tenant"))
        val stream = StreamKey(tenantId, CAMPAIGN_STREAM_TYPE, campaignId)
        val page = events.load(EventStoreRead(stream, afterVersion = 0))
        return page.events
            .mapNotNull(campaignEvents::decode)
            .takeIf(List<CampaignEvent>::isNotEmpty)
            ?.let(CampaignAggregate::replay)
    }

    private fun decideActivation(
        command: ActivateCampaignCommandInput,
        tenantId: TenantId,
        subject: SubjectIdentity,
        hmacKeyVersion: Int,
        now: Instant,
    ): EventSourcedCommandDecision {
        val stream = StreamKey(tenantId, CAMPAIGN_STREAM_TYPE, command.campaignId)
        val page = events.loadInCurrentTransaction(EventStoreRead(stream, afterVersion = 0))
        return when {
            page.committedHead == 0L ->
                rejectedDecision(ReceiptOutcome.CAMPAIGN_NOT_FOUND, hmacKeyVersion, now)
            page.committedHead != command.expectedRevision + 1 ->
                rejectedDecision(ReceiptOutcome.STALE_REVISION, hmacKeyVersion, now)
            else ->
                decideActivation(
                    campaign = CampaignAggregate.replay(page.events.mapNotNull(campaignEvents::decode)),
                    context =
                        CampaignActivationContext(
                            stream = stream,
                            expectedVersion = page.committedHead,
                            subject = subject,
                            hmacKeyVersion = hmacKeyVersion,
                            now = now,
                        ),
                )
        }
    }

    private fun decideActivation(
        campaign: CampaignAggregate,
        context: CampaignActivationContext,
    ): EventSourcedCommandDecision =
        try {
            acceptedActivation(
                event = CampaignCommands.activate(campaign).events.single(),
                context = context,
            )
        } catch (_: DomainTransitionException) {
            rejectedDecision(ReceiptOutcome.DOMAIN_REJECTED, context.hmacKeyVersion, context.now)
        }

    private fun acceptedActivation(
        event: CampaignEvent,
        context: CampaignActivationContext,
    ): EventSourcedCommandDecision =
        EventSourcedCommandDecision(
            appends =
                listOf(
                    ExpectedAppend(
                        stream = context.stream,
                        expectedVersion = context.expectedVersion,
                        events = listOf(event.toAppend(context.subject, context.now)),
                    ),
                ),
            descriptor =
                TerminalDescriptor(
                    outcome = ReceiptOutcome.CAMPAIGN_ACTIVATED,
                    status = CAMPAIGN_TRANSITION_STATUS,
                    keyVersions = TerminalKeyVersions(hmac = context.hmacKeyVersion),
                ).withObservedAt(context.now),
        )

    private fun rejectedDecision(
        outcome: ReceiptOutcome,
        hmacKeyVersion: Int,
        now: Instant,
    ): EventSourcedCommandDecision =
        EventSourcedCommandDecision(
            appends = emptyList(),
            descriptor =
                TerminalDescriptor(
                    outcome = outcome,
                    status = CAMPAIGN_CONFLICT_STATUS,
                    keyVersions = TerminalKeyVersions(hmac = hmacKeyVersion),
                ).withObservedAt(now),
        )

    private fun fingerprint(input: CreateCampaignCommandInput): ReceiptDigest =
        ReceiptDigest.sha256(
            "voucher-campaign-create-v1\u0000" +
                mapper.writeValueAsString(
                    CampaignCreatedPayload(
                        input.startsAt,
                        input.endsAt,
                        input.capacity,
                        input.perUserLimit,
                        input.redemptionTtlSeconds,
                    ),
                ),
        )

    private fun CampaignEvent.toAppend(
        subject: SubjectIdentity,
        occurredAt: Instant,
    ): EventToAppend {
        val eventId = Uuid.V7.nextUUID()
        val serialized = campaignEvents.encode(this)
        return EventToAppend(
            eventId = eventId,
            eventType = serialized.eventType,
            schemaVersion = 1,
            payload = serialized.payload,
            occurredAt = occurredAt,
            correlationId = eventId.toString(),
            actorSurrogate = subject.surrogate.toString(),
            actorHmacKeyVersion = subject.hmacKeyVersion,
        )
    }
}

private data class CampaignCreatedPayload(
    val startsAt: Instant,
    val endsAt: Instant,
    val capacity: Int,
    val perUserLimit: Int,
    val redemptionTtlSeconds: Long,
)

private fun CreateCampaignCommandInput.toEvent(tenantId: TenantId): CampaignEvent.CampaignCreated =
    CampaignEvent.CampaignCreated(
        tenantId = tenantId,
        campaignId = campaignId,
        startsAt = startsAt,
        endsAt = endsAt,
        capacity = capacity,
        perUserLimit = perUserLimit,
        redemptionTtlSeconds = redemptionTtlSeconds,
    )

private fun CommandExecutionResult.toCampaignExecution(): CampaignCommandExecution =
    when (this) {
        is CommandExecutionResult.Executed -> CampaignCommandExecution.Completed(descriptor, replayed = false)
        is CommandExecutionResult.Replayed -> CampaignCommandExecution.Completed(descriptor, replayed = true)
        is CommandExecutionResult.InProgress -> CampaignCommandExecution.InProgress
        is CommandExecutionResult.FingerprintConflict -> CampaignCommandExecution.FingerprintConflict
        is CommandExecutionResult.KeyUnavailable -> CampaignCommandExecution.KeyUnavailable
    }

private fun CreateCampaignCommandInput.validated(): CreateCampaignCommandInput {
    val validKey = idempotencyKey.validIdempotencyKey()
    return copy(
        tenant = tenant.validCommandIdentity("tenant"),
        principal = principal.validCommandIdentity("principal"),
        idempotencyKey = validKey,
    )
}

private fun ActivateCampaignCommandInput.validated(): ActivateCampaignCommandInput =
    copy(
        tenant = tenant.validCommandIdentity("tenant"),
        principal = principal.validCommandIdentity("principal"),
        idempotencyKey = idempotencyKey.validIdempotencyKey(),
        expectedRevision = expectedRevision.requireZeroOrPositiveNumber("expectedRevision"),
    )
