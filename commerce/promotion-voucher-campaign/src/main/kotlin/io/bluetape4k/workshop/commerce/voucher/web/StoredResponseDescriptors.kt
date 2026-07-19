package io.bluetape4k.workshop.commerce.voucher.web

import io.bluetape4k.workshop.commerce.voucher.domain.CampaignSnapshot
import io.bluetape4k.workshop.commerce.voucher.domain.ClaimSnapshot
import io.bluetape4k.workshop.commerce.voucher.idempotency.StoredHttpResponse
import io.bluetape4k.workshop.commerce.voucher.idempotency.VoucherResponseKind
import java.time.Instant
import java.util.UUID

/** Closed, bounded metadata used to reproduce the original HTTP body without storing raw JSON. */
internal fun CampaignSnapshot.storedCampaignResponse(
    kind: VoucherResponseKind,
    status: Int,
    publicHeaders: Map<String, String> = emptyMap(),
): StoredHttpResponse =
    StoredHttpResponse(
        kind,
        status,
        publicHeaders + (CAMPAIGN_DESCRIPTOR_HEADER to campaignDescriptor()),
        campaignId,
        null,
        revision,
        null,
        null,
    )

internal fun ClaimSnapshot.storedClaimResponse(
    kind: VoucherResponseKind,
    status: Int = 200,
    publicHeaders: Map<String, String> = emptyMap(),
    allocationId: UUID? = null,
    generationKeyVersion: Int? = null,
    verificationKeyVersion: Int? = null,
): StoredHttpResponse =
    StoredHttpResponse(
        kind,
        status,
        publicHeaders + (CLAIM_DESCRIPTOR_HEADER to claimDescriptor()),
        claimId,
        allocationId,
        revision,
        generationKeyVersion,
        verificationKeyVersion,
    )

internal fun ClaimSnapshot.storedAllocationResponse(
    kind: VoucherResponseKind,
    status: Int,
    allocationId: UUID,
    reviewId: Long?,
    generationKeyVersion: Int?,
    verificationKeyVersion: Int?,
): StoredHttpResponse =
    StoredHttpResponse(
        kind,
        status,
        mapOf(
            "Location" to "/api/v1/claims/$claimId",
            ALLOCATION_DESCRIPTOR_HEADER to allocationDescriptor(reviewId),
        ),
        claimId,
        allocationId,
        revision,
        generationKeyVersion,
        verificationKeyVersion,
    )

internal fun StoredHttpResponse.campaignBody(): CampaignHttpResponse {
    val values = descriptor(CAMPAIGN_DESCRIPTOR_HEADER, 8)
    return CampaignHttpResponse(
        campaignId = aggregateId,
        state = values[0],
        revision = values[1].toLong(),
        policyVersion = values[2].toLong(),
        capacity = values[3].toInt(),
        allocatedCount = values[4].toInt(),
        remainingCapacity = (values[3].toInt() - values[4].toInt()).coerceAtLeast(0),
        startsAt = Instant.ofEpochMilli(values[5].toLong()),
        endsAt = Instant.ofEpochMilli(values[6].toLong()),
        observedAt = Instant.ofEpochMilli(values[7].toLong()),
    )
}

internal fun StoredHttpResponse.claimBody(): ClaimHttpResponse {
    val values = descriptor(CLAIM_DESCRIPTOR_HEADER, 5)
    return ClaimHttpResponse(
        campaignId = UUID.fromString(values[0]),
        claimId = aggregateId,
        state = values[1],
        revision = values[2].toLong(),
        policyVersion = values[3].toLong(),
        expiresAt = values[4].instantOrNull(),
    )
}

internal fun StoredHttpResponse.allocationBody(code: String?): AllocationHttpResponse {
    val values = descriptor(ALLOCATION_DESCRIPTOR_HEADER, 5)
    return AllocationHttpResponse(
        claimId = aggregateId,
        state = values[0],
        revision = values[1].toLong(),
        policyVersion = values[2].toLong(),
        expiresAt = values[3].instantOrNull(),
        reviewId = values[4].longOrNull(),
        code = code,
    )
}

internal fun String.isStoredDescriptorHeader(): Boolean =
    this == CAMPAIGN_DESCRIPTOR_HEADER ||
        this == CLAIM_DESCRIPTOR_HEADER ||
        this == ALLOCATION_DESCRIPTOR_HEADER ||
        this == RECONCILIATION_DESCRIPTOR_HEADER

private fun CampaignSnapshot.campaignDescriptor(): String =
    listOf(
        state.name,
        revision,
        policyVersion,
        capacity,
        allocatedCount,
        startsAt.toEpochMilli(),
        endsAt.toEpochMilli(),
        Instant.now().toEpochMilli(),
    ).joinToString(DESCRIPTOR_SEPARATOR)

private fun ClaimSnapshot.claimDescriptor(): String =
    listOf(campaignId, state.name, revision, allocationPolicyVersion, expiresAt?.toEpochMilli() ?: NULL_VALUE)
        .joinToString(DESCRIPTOR_SEPARATOR)

private fun ClaimSnapshot.allocationDescriptor(reviewId: Long?): String =
    listOf(
        state.name,
        revision,
        allocationPolicyVersion,
        expiresAt?.toEpochMilli() ?: NULL_VALUE,
        reviewId ?: NULL_VALUE,
    ).joinToString(DESCRIPTOR_SEPARATOR)

private fun StoredHttpResponse.descriptor(
    header: String,
    expectedSize: Int,
): List<String> =
    requireNotNull(headers[header]) { "stored response descriptor is missing" }
        .split(DESCRIPTOR_SEPARATOR)
        .also { require(it.size == expectedSize) { "stored response descriptor is invalid" } }

private fun String.instantOrNull(): Instant? = takeUnless { it == NULL_VALUE }?.toLong()?.let(Instant::ofEpochMilli)

private fun String.longOrNull(): Long? = takeUnless { it == NULL_VALUE }?.toLong()

internal const val RECONCILIATION_DESCRIPTOR_HEADER = "X-Workshop-Reconciliation-Result"
private const val CAMPAIGN_DESCRIPTOR_HEADER = "X-Workshop-Campaign-Descriptor"
private const val CLAIM_DESCRIPTOR_HEADER = "X-Workshop-Claim-Descriptor"
private const val ALLOCATION_DESCRIPTOR_HEADER = "X-Workshop-Allocation-Descriptor"
private const val DESCRIPTOR_SEPARATOR = "|"
private const val NULL_VALUE = "-"
