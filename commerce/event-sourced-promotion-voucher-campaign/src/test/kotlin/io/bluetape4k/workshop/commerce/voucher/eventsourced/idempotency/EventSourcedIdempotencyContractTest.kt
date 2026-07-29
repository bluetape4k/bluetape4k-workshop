package io.bluetape4k.workshop.commerce.voucher.eventsourced.idempotency

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import org.junit.jupiter.api.Test
import java.util.UUID

internal class EventSourcedIdempotencyContractTest {

    @Test
    fun `receipt scope separates principals and resources`() {
        // 준비
        val first = scope(principal = "principal-a")
        val second = scope(principal = "principal-b")

        // 실행 / 검증
        (first == second).shouldBeFalse()
    }

    @Test
    fun `receipt digest restores the persisted canonical representation`() {
        val digest = ReceiptDigest.sha256("projection-generation")

        ReceiptDigest.of(digest.value) shouldBeEqualTo digest
    }

    @Test
    fun `terminal replay fails closed when its generation key is unavailable`() {
        // 준비
        val descriptor =
            TerminalDescriptor(
                outcome = ReceiptOutcome.VOUCHER_ALLOCATED,
                status = 201,
                allocationId = UUID.fromString("0198a1b2-c3d4-7e5f-8123-456789abc001"),
                keyVersions =
                    TerminalKeyVersions(
                        generationKeyVersion = 3,
                        verificationKeyVersion = 3,
                    ),
            )

        // 실행
        val replay = descriptor.replayWith { version -> version != 3 }

        // 검증
        replay shouldBeEqualTo TerminalReplay.KeyUnavailable
    }

    private fun scope(principal: String) =
        ReceiptScope(
            tenantId = TenantId("tenant-a"),
            principalDigest = ReceiptDigest.sha256(principal),
            operation = "campaign.create",
            resourceId = "campaign-a",
            keyDigest = ReceiptDigest.sha256("key-a"),
        )
}
