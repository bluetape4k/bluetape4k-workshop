package io.bluetape4k.workshop.commerce.voucher.eventsourced.serialization

import io.bluetape4k.jackson3.jsonMapper
import io.bluetape4k.support.requireEquals
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventEnvelope
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.EventPayload
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherCodeKeyVersions
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.VoucherEvent
import java.time.Instant
import java.util.UUID

internal const val VOUCHER_STREAM_TYPE = "voucher"

internal data class SerializedVoucherEvent(
    val eventType: String,
    val payload: EventPayload,
)

internal class VoucherEventCodec {
    private val mapper = jsonMapper { }

    fun encode(event: VoucherEvent): SerializedVoucherEvent =
        when (event) {
            is VoucherEvent.VoucherIssued ->
                SerializedVoucherEvent(
                    "voucher.issued",
                    EventPayload(
                        mapper.writeValueAsString(
                            VoucherIssuedPayload(
                                campaignId = event.campaignId,
                                subjectId = event.subjectId,
                                generationKeyVersion = event.codeKeyVersions.generation,
                                verificationKeyVersion = event.codeKeyVersions.verification,
                            ),
                        ),
                    ),
                )

            is VoucherEvent.VoucherAllocated ->
                SerializedVoucherEvent(
                    "voucher.allocated",
                    EventPayload(
                        mapper.writeValueAsString(
                            VoucherAllocatedPayload(event.policyVersion, event.expiresAt),
                        ),
                    ),
                )

            is VoucherEvent.VoucherRedeemed ->
                SerializedVoucherEvent(
                    "voucher.redeemed",
                    EventPayload(mapper.writeValueAsString(VoucherRedeemedPayload(event.redeemedAt))),
                )

            VoucherEvent.VoucherReleased ->
                SerializedVoucherEvent("voucher.released", EventPayload("{}"))
            VoucherEvent.VoucherExpired ->
                SerializedVoucherEvent("voucher.expired", EventPayload("{}"))
            VoucherEvent.VoucherRevoked ->
                SerializedVoucherEvent("voucher.revoked", EventPayload("{}"))
        }

    fun decode(event: EventEnvelope): VoucherEvent? {
        if (event.stream.type != VOUCHER_STREAM_TYPE) return null
        event.schemaVersion.requireEquals(1, "${event.eventType}.schemaVersion")
        return when (event.eventType) {
            "voucher.issued" ->
                mapper
                    .readValue(event.payload.canonicalJson, VoucherIssuedPayload::class.java)
                    .let { payload ->
                        VoucherEvent.VoucherIssued(
                            tenantId = event.tenantId,
                            campaignId = payload.campaignId,
                            voucherId = event.stream.id,
                            subjectId = payload.subjectId,
                            codeKeyVersions =
                                VoucherCodeKeyVersions(
                                    generation = payload.generationKeyVersion,
                                    verification = payload.verificationKeyVersion,
                                ),
                        )
                    }

            "voucher.allocated" ->
                mapper
                    .readValue(event.payload.canonicalJson, VoucherAllocatedPayload::class.java)
                    .let { VoucherEvent.VoucherAllocated(it.policyVersion, it.expiresAt) }

            "voucher.redeemed" ->
                mapper
                    .readValue(event.payload.canonicalJson, VoucherRedeemedPayload::class.java)
                    .let { VoucherEvent.VoucherRedeemed(it.redeemedAt) }

            "voucher.released" -> VoucherEvent.VoucherReleased
            "voucher.expired" -> VoucherEvent.VoucherExpired
            "voucher.revoked" -> VoucherEvent.VoucherRevoked
            else -> error("unsupported voucher event type")
        }
    }
}

private data class VoucherIssuedPayload(
    val campaignId: UUID,
    val subjectId: UUID,
    val generationKeyVersion: Int,
    val verificationKeyVersion: Int,
)

private data class VoucherAllocatedPayload(
    val policyVersion: Long,
    val expiresAt: Instant,
)

private data class VoucherRedeemedPayload(
    val redeemedAt: Instant,
)
