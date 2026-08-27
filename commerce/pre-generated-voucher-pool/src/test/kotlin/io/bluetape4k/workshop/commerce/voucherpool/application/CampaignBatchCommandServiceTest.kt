package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.workshop.commerce.voucherpool.persistence.DigestValue
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcExecutionLane
import io.bluetape4k.workshop.commerce.voucherpool.persistence.JdbcTimeoutPhase
import io.bluetape4k.workshop.commerce.voucherpool.persistence.VoucherPoolJdbcTimeoutException
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import org.postgresql.util.ServerErrorMessage
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

internal class CampaignBatchCommandServiceTest {
    @Test
    fun `batch and chunk commands enforce bounded input before JDBC`() {
        assertFailsWith<IllegalArgumentException> {
            importBatch(expectedCount = 10_001)
        }
        assertFailsWith<IllegalArgumentException> {
            importChunk(codes = List(501) { "CODE-$it" })
        }
        assertFailsWith<IllegalArgumentException> {
            GenerateChunkCommand(TENANT, BATCH, CAMPAIGN, 0, MANIFEST, 501, REVISION, IDEMPOTENCY_KEY)
        }
        assertFailsWith<IllegalArgumentException> {
            importChunk(codes = listOf("x".repeat(4 * 1_024 * 1_024 + 1)))
        }
    }

    @Test
    fun `lifecycle command defaults use UUID version seven`() {
        AllocateVoucherCommand(
            tenantId = TENANT,
            campaignId = CAMPAIGN,
            reservationId = BATCH,
            canonicalUser = "user-1",
            expectedRevision = REVISION,
            idempotencyKey = IDEMPOTENCY_KEY,
        ).allocationId.version().shouldBeEqualTo(7)

        ReplaceLostRevealCommand(
            tenantId = TENANT,
            campaignId = CAMPAIGN,
            allocationId = BATCH,
            canonicalUser = "user-1",
            expectedRevision = REVISION,
            idempotencyKey = IDEMPOTENCY_KEY,
        ).reservationId.version().shouldBeEqualTo(7)

        ReserveVoucherCommand(
            tenantId = TENANT,
            campaignId = CAMPAIGN,
            canonicalUser = "user-1",
            idempotencyKey = IDEMPOTENCY_KEY,
        ).reservationId.version().shouldBeEqualTo(7)
    }

    @Test
    fun `import and generation commands reject invalid ordinals and empty work`() {
        assertFailsWith<IllegalArgumentException> { importChunk(firstOrdinal = -1) }
        assertFailsWith<IllegalArgumentException> { importChunk(codes = emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            GenerateChunkCommand(TENANT, BATCH, CAMPAIGN, -1, MANIFEST, 1, REVISION, IDEMPOTENCY_KEY)
        }
        assertFailsWith<IllegalArgumentException> {
            GenerateChunkCommand(TENANT, BATCH, CAMPAIGN, 0, MANIFEST, 0, REVISION, IDEMPOTENCY_KEY)
        }
        assertFailsWith<IllegalArgumentException> {
            importChunk(expectedRevision = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            GenerateChunkCommand(TENANT, BATCH, CAMPAIGN, 0, MANIFEST, 1, -1, IDEMPOTENCY_KEY)
        }
    }

    @Test
    fun `known deterministic generation is rejected outside loopback test mode`() {
        val deterministic = object : GeneratedVoucherCodeSource {
            override val deterministic: Boolean = true
            override fun nextCode(): String = "KNOWN-CODE"
        }

        assertFailsWith<IllegalArgumentException> {
            JdbcCampaignBatchCommandService(
                executor = mockk(),
                repository = mockk(),
                idempotency = mockk(),
                digests = mockk(),
                crypto = mockk(),
                generatedCodes = deterministic,
                runtimeProfile = VoucherPoolRuntimeProfile.PRODUCTION,
            )
        }
    }

    @Test
    fun `secure generation provides at least 128 bits without exposing a seed`() {
        val source = SecureRandomVoucherCodeSource()
        val generated = List(100) { source.nextCode() }

        source.deterministic.shouldBeFalse()
        generated.distinct() shouldHaveSize generated.size
        generated.all { it.startsWith("VP-") && it.removePrefix("VP-").length >= 22 }.shouldBeTrue()
        source.toString().contains("seed", ignoreCase = true).shouldBeFalse()
    }

    @Test
    fun `constraint authority comes from nested PostgreSQL metadata and never exception text`() {
        val postgresFailure = PSQLException(
            ServerErrorMessage(
                "SERROR\u0000C23505\u0000Mduplicate key\u0000nuq_voucher_pool_batch_identity\u0000\u0000",
            ),
        )
        val sqlWrapper = SQLException("outer SQL wrapper").apply { setNextException(postgresFailure) }
        val nested = IllegalStateException("application wrapper", sqlWrapper)

        nested.postgresConstraintName() shouldBeEqualTo "uq_voucher_pool_batch_identity"
        SQLException("violates constraint \"uq_voucher_pool_batch_identity\"")
            .postgresConstraintName().shouldBeNull()
    }

    @Test
    fun `retryable owner release survives bounded JDBC timeouts`() {
        val attempts = AtomicInteger()

        releaseRetryableOwner {
            if (attempts.incrementAndGet() < 4) throw jdbcTimeout()
        }

        attempts.get() shouldBeEqualTo 4
    }

    @Test
    fun `retryable owner release fails after its bounded retry budget`() {
        val attempts = AtomicInteger()

        assertFailsWith<VoucherPoolJdbcTimeoutException> {
            releaseRetryableOwner {
                attempts.incrementAndGet()
                throw jdbcTimeout()
            }
        }

        attempts.get() shouldBeEqualTo 8
    }

    private fun importBatch(expectedCount: Long) = CreateImportBatchCommand(
        tenantId = TENANT,
        batchId = BATCH,
        campaignId = CAMPAIGN,
        sourceKind = BatchSourceKind.IMPORTED,
        manifestDigest = MANIFEST,
        requestFingerprint = DigestValue.of(ByteArray(32) { 2 }),
        expectedCount = expectedCount,
        activatesAt = Instant.EPOCH,
        initialCodes = listOf("CODE-0"),
        idempotencyKey = IDEMPOTENCY_KEY,
    )

    private fun importChunk(
        firstOrdinal: Long = 0,
        codes: List<String> = listOf("CODE-0"),
        expectedRevision: Long = REVISION,
    ) = ImportChunkCommand(TENANT, BATCH, CAMPAIGN, firstOrdinal, MANIFEST, codes, expectedRevision, IDEMPOTENCY_KEY)

    private fun jdbcTimeout() =
        VoucherPoolJdbcTimeoutException(
            JdbcExecutionLane.OPERATOR,
            JdbcTimeoutPhase.TRANSACTION,
            TimeoutException("owner release timeout"),
        )

    companion object {
        private const val TENANT = "tenant-a"
        private val CAMPAIGN = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val BATCH = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private val MANIFEST = DigestValue.of(ByteArray(32) { 1 })
        private const val REVISION = 3L
        private const val IDEMPOTENCY_KEY = "unit-idempotency-key"
    }
}
