package io.bluetape4k.workshop.commerce.voucherpool.query

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.workshop.commerce.voucherpool.domain.ReservationState
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcExecutor
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKey
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestKeyRing
import io.bluetape4k.workshop.commerce.voucherpool.security.DigestPurpose
import io.bluetape4k.workshop.commerce.voucherpool.security.VoucherDigestService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFailsWith

internal class VoucherPoolQueryServiceTest {
    private val executor = mockk<VoucherPoolJdbcExecutor>()
    private val store = mockk<VoucherPoolQueryStore>()
    private val digests = testDigests()
    private val service = JdbcVoucherPoolQueryService(executor, store, digests)

    @Test
    fun `customer reservation uses foreground lane and final owner digest predicate`() {
        val reservationId = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        val expected = reservation(reservationId, campaignId)
        var ownerDigest: ByteArray? = null
        foregroundTransactionsExecuteInline()
        every { store.resolveReservationCampaign(TENANT, reservationId) } returns campaignId
        every { store.findUserIdentityKeyVersion(TENANT, campaignId) } returns digests.currentUserIdentityKeyVersion
        every { store.findOwnedReservation(TENANT, reservationId, any()) } answers {
            ownerDigest = thirdArg<ByteArray>().copyOf()
            expected
        }

        service.reservation(TENANT, PRINCIPAL, reservationId) shouldBeEqualTo expected

        checkNotNull(ownerDigest).contentEquals(
            digests.userIdentity(TENANT, campaignId, PRINCIPAL).copyBytes(),
        ) shouldBeEqualTo true
        verify(exactly = 1) { executor.foregroundTransaction<ReservationReadModel?>(any()) }
        verify(exactly = 0) { executor.operatorTransaction<Any?>(any()) }
    }

    @Test
    fun `missing or wrong owner reservation stays indistinguishable`() {
        val reservationId = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        foregroundTransactionsExecuteInline()
        every { store.resolveReservationCampaign(TENANT, reservationId) } returns campaignId
        every { store.findUserIdentityKeyVersion(TENANT, campaignId) } returns digests.currentUserIdentityKeyVersion
        every { store.findOwnedReservation(TENANT, reservationId, any()) } returns null

        service.reservation(TENANT, "wrong-principal", reservationId) shouldBeEqualTo null

        every { store.resolveReservationCampaign(TENANT, reservationId) } returns null
        service.reservation(TENANT, PRINCIPAL, reservationId) shouldBeEqualTo null
    }

    @Test
    fun `operator reads use operator lane and bounded stuck page`() {
        val batchId = UUID.randomUUID()
        val campaignId = UUID.randomUUID()
        val page = StuckReservationPage(emptyList(), null, OBSERVED_AT)
        operatorTransactionsExecuteInline()
        every { store.findCampaign(TENANT, campaignId) } returns null
        every { store.findBatch(TENANT, batchId) } returns null
        every { store.scopeExists(TENANT, null, null) } returns true
        every { store.findStuckReservations(TENANT, null, null, 100) } returns page

        service.campaign(TENANT, campaignId) shouldBeEqualTo null
        service.batch(TENANT, batchId) shouldBeEqualTo null
        service.stuckReservations(TENANT, null, null, 100) shouldBeEqualTo page

        verify(exactly = 3) { executor.operatorTransaction<Any?>(any()) }
        verify(exactly = 0) { executor.foregroundTransaction<Any?>(any()) }
    }

    @Test
    fun `operator scoped reads hide missing resources`() {
        val campaignId = UUID.randomUUID()
        val batchId = UUID.randomUUID()
        operatorTransactionsExecuteInline()
        every { store.scopeExists(TENANT, campaignId, batchId) } returns false
        every { store.scopeExists(TENANT, campaignId, null) } returns false

        service.poolDepth(TENANT, campaignId, batchId) shouldBeEqualTo null
        service.stuckReservations(TENANT, campaignId, null, 10) shouldBeEqualTo null

        verify(exactly = 0) { store.readPoolDepth(any(), any(), any()) }
        verify(exactly = 0) { store.findStuckReservations(any(), any(), any(), any()) }
    }

    @Test
    fun `stuck reservation page size is bounded before opening a transaction`() {
        assertFailsWith<IllegalArgumentException> {
            service.stuckReservations(TENANT, null, null, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            service.stuckReservations(TENANT, null, null, 101)
        }

        verify(exactly = 0) { executor.operatorTransaction<Any?>(any()) }
    }

    private fun foregroundTransactionsExecuteInline() {
        every { executor.foregroundTransaction<ReservationReadModel?>(any()) } answers {
            firstArg<() -> ReservationReadModel?>().invoke()
        }
    }

    private fun operatorTransactionsExecuteInline() {
        every { executor.operatorTransaction<Any?>(any()) } answers { firstArg<() -> Any?>().invoke() }
    }

    private fun reservation(reservationId: UUID, campaignId: UUID) =
        ReservationReadModel(
            reservationId = reservationId,
            campaignId = campaignId,
            batchId = UUID.randomUUID(),
            entryId = UUID.randomUUID(),
            state = ReservationState.ACTIVE,
            expiresAt = OBSERVED_AT.plusSeconds(60),
            entitlementRootId = null,
            replacementOrdinal = 0,
            policyVersion = 1,
            revision = 0,
            observedAt = OBSERVED_AT,
        )

    private fun testDigests(): VoucherDigestService =
        VoucherDigestService(
            stableDedupKey = DigestKey.of(1, material(1)),
            commandTombstoneKey = DigestKey.of(2, material(2)),
            rotatingKeys =
                setOf(
                    DigestPurpose.VERIFICATION,
                    DigestPurpose.USER_IDENTITY,
                    DigestPurpose.REDIS_SIGNAL,
                    DigestPurpose.AUDIT,
                ).associateWith { purpose ->
                    val seed = 10 + purpose.ordinal
                    DigestKeyRing.of(DigestKey.of(seed, material(seed)))
                },
        )

    private fun material(seed: Int): ByteArray = ByteArray(32) { index -> (seed + index).toByte() }

    private companion object {
        const val TENANT = "tenant-query"
        const val PRINCIPAL = "principal-query"
        val OBSERVED_AT: Instant = Instant.parse("2026-07-21T00:00:00Z")
    }
}
