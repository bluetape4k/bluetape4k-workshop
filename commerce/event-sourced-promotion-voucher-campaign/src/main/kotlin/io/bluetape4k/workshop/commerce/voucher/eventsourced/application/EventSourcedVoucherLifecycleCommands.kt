package io.bluetape4k.workshop.commerce.voucher.eventsourced.application

import io.bluetape4k.idgenerators.uuid.Uuid
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.support.requireZeroOrPositiveNumber
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherAggregate
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherEvent
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherState
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
import io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization.VoucherEventCodec
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val SUCCESS_STATUS = 200
private const val CONFLICT_STATUS = 409
private const val PRECONDITION_FAILED_STATUS = 412
private const val NOT_FOUND_STATUS = 404

internal data class RedeemVoucherCommandInput(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
    val voucherId: UUID,
    val code: String,
    val expectedRevision: Long,
    val redemptionReference: String,
)

internal data class ReleaseVoucherCommandInput(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
    val voucherId: UUID,
    val expectedRevision: Long,
)

internal interface EventSourcedVoucherLifecycleCommands {
    fun redeem(input: RedeemVoucherCommandInput): VoucherCommandExecution

    fun release(input: ReleaseVoucherCommandInput): VoucherCommandExecution
}

internal class DefaultEventSourcedVoucherLifecycleCommands(
    private val commands: EventSourcedCommandService,
    private val identities: SubjectIdentityService,
    private val keyRing: EventSourcedHmacKeyRing,
    private val vouchers: EventSourcedVoucherCommands,
    private val clock: Clock = Clock.systemUTC(),
) : EventSourcedVoucherLifecycleCommands {
    private val voucherEvents = VoucherEventCodec()

    override fun redeem(input: RedeemVoucherCommandInput): VoucherCommandExecution {
        val command = input.validated()
        val now = clock.instant()
        return execute(
            VoucherLifecycleCommandContext(
                tenant = command.tenant,
                principal = command.principal,
                idempotencyKey = command.idempotencyKey,
                voucherId = command.voucherId,
                operation = "REDEEM",
                fingerprintMaterial =
                    listOf(command.code, command.expectedRevision, command.redemptionReference).joinToString("\u0000"),
                now = now,
            ),
        ) { authority ->
            decideRedeem(command, authority, now)
        }
    }

    override fun release(input: ReleaseVoucherCommandInput): VoucherCommandExecution {
        val command = input.validated()
        val now = clock.instant()
        return execute(
            VoucherLifecycleCommandContext(
                tenant = command.tenant,
                principal = command.principal,
                idempotencyKey = command.idempotencyKey,
                voucherId = command.voucherId,
                operation = "RELEASE",
                fingerprintMaterial = command.expectedRevision.toString(),
                now = now,
            ),
        ) { authority ->
            decideRelease(command, authority, now)
        }
    }

    private fun execute(
        context: VoucherLifecycleCommandContext,
        decide: (VoucherLifecycleAuthority) -> EventSourcedCommandDecision,
    ): VoucherCommandExecution {
        val tenantId = TenantId(context.tenant)
        val subject = identities.resolve(tenantId, "voucher-subject", context.principal)
        val principalDigest =
            keyRing.digest(
                HmacPurpose.PRINCIPAL_SCOPE,
                tenantId,
                "voucher-${context.operation}",
                context.principal,
            )
        val keyDigest =
            keyRing.digest(
                HmacPurpose.IDEMPOTENCY_KEY,
                tenantId,
                "voucher-${context.operation}:${context.voucherId}:${principalDigest.value}",
                context.idempotencyKey,
            )
        val scope =
            ReceiptScope(
                tenantId = tenantId,
                principalDigest = ReceiptDigest.of(principalDigest.value),
                operation = context.operation,
                resourceId = context.voucherId.toString(),
                keyDigest = ReceiptDigest.of(keyDigest.value),
            )
        val execution =
            commands.execute(
                EventSourcedCommand(
                    scope = scope,
                    fingerprint =
                        ReceiptDigest.sha256(
                            "voucher-${context.operation}-v1\u0000${context.fingerprintMaterial}",
                        ),
                    acquiredAt = context.now,
                    decideAfterRehydrate = {
                        decide(loadAuthority(tenantId, context.voucherId, subject))
                    },
                ),
            ).toVoucherExecution()
        log.debug {
            "voucher_lifecycle_command operation=${context.operation} voucherId=${context.voucherId}"
        }
        return execution
    }

    private fun loadAuthority(
        tenantId: TenantId,
        voucherId: UUID,
        subject: SubjectIdentity,
    ): VoucherLifecycleAuthority {
        val stream = StreamKey(tenantId, "voucher", voucherId)
        val voucher = vouchers.voucherInCurrentTransaction(tenantId.value, voucherId)
        return VoucherLifecycleAuthority(stream, voucher, subject)
    }

    private fun decideRedeem(
        command: RedeemVoucherCommandInput,
        authority: VoucherLifecycleAuthority,
        now: Instant,
    ): EventSourcedCommandDecision {
        val voucher =
            authority.ownedVoucher()
                ?: return rejected(
                    ReceiptOutcome.VOUCHER_NOT_FOUND,
                    NOT_FOUND_STATUS,
                    authority.subject,
                    command.voucherId,
                    now,
                )
        val outcome =
            when {
                voucher.publicRevision != command.expectedRevision -> ReceiptOutcome.STALE_REVISION
                voucher.state != VoucherState.ALLOCATED -> ReceiptOutcome.INVALID_TRANSITION
                !now.isBefore(checkNotNull(voucher.expiresAt)) -> ReceiptOutcome.VOUCHER_EXPIRED
                !validCode(command, voucher) -> ReceiptOutcome.INVALID_VOUCHER_CODE
                else -> null
            }
        return outcome
            ?.let { rejected(it, it.failureStatus, authority.subject, command.voucherId, now) }
            ?: accepted(VoucherEvent.VoucherRedeemed(now), ReceiptOutcome.VOUCHER_REDEEMED, authority, now)
    }

    private fun decideRelease(
        command: ReleaseVoucherCommandInput,
        authority: VoucherLifecycleAuthority,
        now: Instant,
    ): EventSourcedCommandDecision {
        val voucher =
            authority.ownedVoucher()
                ?: return rejected(
                    ReceiptOutcome.VOUCHER_NOT_FOUND,
                    NOT_FOUND_STATUS,
                    authority.subject,
                    command.voucherId,
                    now,
                )
        val outcome =
            when {
                voucher.publicRevision != command.expectedRevision -> ReceiptOutcome.STALE_REVISION
                voucher.state != VoucherState.ALLOCATED -> ReceiptOutcome.INVALID_TRANSITION
                else -> null
            }
        return outcome
            ?.let { rejected(it, it.failureStatus, authority.subject, command.voucherId, now) }
            ?: accepted(VoucherEvent.VoucherReleased, ReceiptOutcome.VOUCHER_RELEASED, authority, now)
    }

    private fun validCode(
        command: RedeemVoucherCommandInput,
        voucher: VoucherAggregate,
    ): Boolean {
        val expected =
            vouchers.voucherCode(
                tenant = command.tenant,
                campaignId = checkNotNull(voucher.campaignId),
                allocationId = command.voucherId,
                keyVersion = checkNotNull(voucher.verificationKeyVersion),
            )
        return MessageDigest.isEqual(command.code.toByteArray(UTF_8), expected.toByteArray(UTF_8))
    }

    private fun accepted(
        event: VoucherEvent,
        outcome: ReceiptOutcome,
        authority: VoucherLifecycleAuthority,
        now: Instant,
    ): EventSourcedCommandDecision =
        EventSourcedCommandDecision(
            appends =
                listOf(
                    ExpectedAppend(
                        stream = authority.stream,
                        expectedVersion = checkNotNull(authority.voucher).version,
                        events = listOf(event.toAppend(authority.subject, now)),
                    ),
                ),
            descriptor =
                TerminalDescriptor(
                    outcome = outcome,
                    status = SUCCESS_STATUS,
                    keyVersions = TerminalKeyVersions(hmac = authority.subject.hmacKeyVersion),
                    allocationId = checkNotNull(authority.voucher.voucherId),
                ).withObservedAt(now),
        )

    private fun VoucherEvent.toAppend(
        subject: SubjectIdentity,
        now: Instant,
    ): EventToAppend {
        val serialized = voucherEvents.encode(this)
        val eventId = Uuid.V7.nextUUID()
        return EventToAppend(
            eventId = eventId,
            eventType = serialized.eventType,
            schemaVersion = 1,
            payload = EventPayload(serialized.payload.canonicalJson),
            occurredAt = now,
            correlationId = eventId.toString(),
            actorSurrogate = subject.surrogate.toString(),
            actorHmacKeyVersion = subject.hmacKeyVersion,
        )
    }

    private companion object : KLogging()
}

private data class VoucherLifecycleAuthority(
    val stream: StreamKey,
    val voucher: VoucherAggregate?,
    val subject: SubjectIdentity,
) {
    fun ownedVoucher(): VoucherAggregate? = voucher?.takeIf { it.subjectId == subject.surrogate }
}

private val VoucherAggregate.publicRevision: Long get() = version - 2

private val ReceiptOutcome.failureStatus: Int
    get() = if (this == ReceiptOutcome.STALE_REVISION) PRECONDITION_FAILED_STATUS else CONFLICT_STATUS

private fun rejected(
    outcome: ReceiptOutcome,
    status: Int,
    subject: SubjectIdentity,
    voucherId: UUID,
    now: Instant,
): EventSourcedCommandDecision =
    EventSourcedCommandDecision(
        appends = emptyList(),
        descriptor =
            TerminalDescriptor(
                outcome = outcome,
                status = status,
                keyVersions = TerminalKeyVersions(hmac = subject.hmacKeyVersion),
                allocationId = voucherId,
            ).withObservedAt(now),
)

private data class VoucherLifecycleCommandContext(
    val tenant: String,
    val principal: String,
    val idempotencyKey: String,
    val voucherId: UUID,
    val operation: String,
    val fingerprintMaterial: String,
    val now: Instant,
)

private fun RedeemVoucherCommandInput.validated(): RedeemVoucherCommandInput =
    copy(
        tenant = tenant.validCommandIdentity("tenant"),
        principal = principal.validCommandIdentity("principal"),
        idempotencyKey = idempotencyKey.validIdempotencyKey(),
        code = code.validCommandIdentity("code"),
        expectedRevision = expectedRevision.requireZeroOrPositiveNumber("expectedRevision"),
        redemptionReference = redemptionReference.validCommandIdentity("redemptionReference"),
    )

private fun ReleaseVoucherCommandInput.validated(): ReleaseVoucherCommandInput =
    copy(
        tenant = tenant.validCommandIdentity("tenant"),
        principal = principal.validCommandIdentity("principal"),
        idempotencyKey = idempotencyKey.validIdempotencyKey(),
        expectedRevision = expectedRevision.requireZeroOrPositiveNumber("expectedRevision"),
    )
