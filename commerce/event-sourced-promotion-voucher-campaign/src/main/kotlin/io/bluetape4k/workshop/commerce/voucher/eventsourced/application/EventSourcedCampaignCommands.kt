package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.support.requireInRange
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.CampaignEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptDigest
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptOutcome
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.ReceiptScope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalDescriptor
import io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency.TerminalKeyVersions
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.EventToAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.ExpectedAppend
import io.bluetape4k.workshop.commerce.voucher.eventsourced.persistence.StreamKey
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.EventSourcedHmacKeyRing
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.HmacPurpose
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentity
import io.bluetape4k.workshop.commerce.voucher.eventsourced.security.SubjectIdentityService
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val CAMPAIGN_STREAM_TYPE = "campaign"
private const val CAMPAIGN_CREATED_EVENT_TYPE = "campaign.created"
private const val CAMPAIGN_CREATED_STATUS = 201
private const val MIN_IDEMPOTENCY_KEY_LENGTH = 8
private const val MAX_IDEMPOTENCY_KEY_LENGTH = 200

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

internal sealed interface CampaignCommandExecution {
    data class Completed(
        val descriptor: TerminalDescriptor,
        val replayed: Boolean,
    ) : CampaignCommandExecution

    data object InProgress : CampaignCommandExecution

    data object FingerprintConflict : CampaignCommandExecution

    data object KeyUnavailable : CampaignCommandExecution
}

internal fun interface EventSourcedCampaignCommands {
    fun create(input: CreateCampaignCommandInput): CampaignCommandExecution
}

/** Converts one validated campaign command into the generic receipt plus append orchestration boundary. */
internal class DefaultEventSourcedCampaignCommands(
    private val commands: EventSourcedCommandService,
    private val identities: SubjectIdentityService,
    private val keyRing: EventSourcedHmacKeyRing,
    private val clock: Clock = Clock.systemUTC(),
) : EventSourcedCampaignCommands {
    private val mapper = jsonMapper { }

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

    private fun CampaignEvent.CampaignCreated.toAppend(
        subject: SubjectIdentity,
        occurredAt: Instant,
    ): EventToAppend {
        val eventId = Uuid.V7.nextUUID()
        return EventToAppend(
            eventId = eventId,
            eventType = CAMPAIGN_CREATED_EVENT_TYPE,
            schemaVersion = 1,
            payload =
                EventPayload(
                    mapper.writeValueAsString(
                        CampaignCreatedPayload(
                            startsAt,
                            endsAt,
                            capacity,
                            perUserLimit,
                            redemptionTtlSeconds,
                        ),
                    ),
                ),
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
    val validKey = idempotencyKey.requireNotBlank("Idempotency-Key")
    validKey.length.requireInRange(
        MIN_IDEMPOTENCY_KEY_LENGTH,
        MAX_IDEMPOTENCY_KEY_LENGTH,
        "Idempotency-Key.length",
    )
    return copy(
        tenant = tenant.requireNotBlank("tenant"),
        principal = principal.requireNotBlank("principal"),
        idempotencyKey = validKey,
    )
}
