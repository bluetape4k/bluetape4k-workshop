package io.bluetape4k.workshop.commerce.voucher.eventsourced.security

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.workshop.commerce.voucher.eventsourced.domain.TenantId
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

internal class EventSourcedHmacKeyRingTest {
    private val keyRing =
        EventSourcedHmacKeyRing(
            active = EventSourcedHmacKey(2, "active-key-material-with-at-least-32-bytes".toByteArray()),
            retired =
                listOf(
                    EventSourcedHmacKey(1, "retired-key-material-with-at-least-32-bytes".toByteArray()),
                ),
        )

    @Test
    fun `active digest is deterministic and domain separated`() {
        val digest =
            keyRing.digest(
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = TenantId("tenant-a"),
                domain = "campaign-principal",
                value = "user-42",
            )

        digest shouldBeEqualTo
            keyRing.digest(
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = TenantId("tenant-a"),
                domain = "campaign-principal",
                value = "user-42",
            )
        digest.keyVersion shouldBeEqualTo 2
        digest shouldNotBeEqualTo
            keyRing.digest(
                purpose = HmacPurpose.IDEMPOTENCY_KEY,
                tenantId = TenantId("tenant-a"),
                domain = "campaign-principal",
                value = "user-42",
            )
        digest shouldNotBeEqualTo
            keyRing.digest(
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = TenantId("tenant-b"),
                domain = "campaign-principal",
                value = "user-42",
            )
    }

    @Test
    fun `lookup candidates retain retired keys while unavailable versions fail closed`() {
        val candidates =
            keyRing.digestsForLookup(
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = TenantId("tenant-a"),
                domain = "campaign-principal",
                value = "user-42",
            )

        candidates.map(KeyedDigest::keyVersion) shouldBeEqualTo listOf(2, 1)
        keyRing.isAvailable(1).shouldBeTrue()
        keyRing.isAvailable(3).shouldBeFalse()
        assertFailsWith<EventSourcedKeyUnavailableException> {
            keyRing.digestWithVersion(
                keyVersion = 3,
                purpose = HmacPurpose.SUBJECT_IDENTITY,
                tenantId = TenantId("tenant-a"),
                domain = "campaign-principal",
                value = "user-42",
            )
        }
    }

    @Test
    fun `configuration retains verification keys only until their replay retention deadline`() {
        val encoded = Base64.getEncoder().encodeToString("configuration-key-material-with-32-bytes".toByteArray())
        val clock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC)
        val configured =
            EventSourcedIdentitySecurityConfiguration().eventSourcedHmacKeyRing(
                properties =
                    EventSourcedHmacProperties(
                        activeVersion = 2,
                        activeKeyBase64 = encoded,
                        retired =
                            listOf(
                                RetiredHmacKeyProperties(1, encoded, Instant.parse("2026-08-23T00:00:00Z")),
                                RetiredHmacKeyProperties(3, encoded, Instant.parse("2026-06-23T00:00:00Z")),
                            ),
                    ),
                clock = clock,
            )

        configured.isAvailable(1).shouldBeTrue()
        configured.isAvailable(2).shouldBeTrue()
        configured.isAvailable(3).shouldBeFalse()
    }
}
