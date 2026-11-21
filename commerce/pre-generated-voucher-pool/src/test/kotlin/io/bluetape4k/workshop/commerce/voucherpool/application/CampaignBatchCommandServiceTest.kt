package io.bluetape4k.workshop.commerce.voucherpool.application

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotContainIgnoringCase
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
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
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * CampaignBatchCommandServiceTest 테스트 묶음은 사전 생성 바우처 풀의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class CampaignBatchCommandServiceTest {

    companion object: KLogging() {
/**
 * TENANT은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val TENANT = "tenant-a"
/**
 * CAMPAIGN은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val CAMPAIGN = UUID.fromString("11111111-1111-1111-1111-111111111111")
/**
 * BATCH은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val BATCH = UUID.fromString("22222222-2222-2222-2222-222222222222")
/**
 * MANIFEST은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private val MANIFEST = DigestValue.of(ByteArray(32) { 1 })
/**
 * REVISION은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val REVISION = 3L
/**
 * IDEMPOTENCY_KEY은 사전 생성 바우처 풀 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        private const val IDEMPOTENCY_KEY = "unit-idempotency-key"
    }

    @Test
/**
 * 이 시나리오는 "batch and chunk commands enforce bounded input before JDBC" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `batch and chunk commands enforce bounded input before JDBC`() {
        assertFailsWith<IllegalArgumentException> {
            importBatch(expectedCount = 10_001)
        }
        assertFailsWith<IllegalArgumentException> {
            importChunk(codes = List(501) { "CODE-$it" })
        }
        assertFailsWith<IllegalArgumentException> {
            GenerateChunkCommand(
                tenantId = TENANT,
                batchId = BATCH,
                campaignId = CAMPAIGN,
                firstOrdinal = 0,
                manifestDigest = MANIFEST,
                count = 501,
                expectedRevision = REVISION,
                idempotencyKey = IDEMPOTENCY_KEY
            )
        }
        assertFailsWith<IllegalArgumentException> {
            importChunk(codes = listOf("x".repeat(4 * 1_024 * 1_024 + 1)))
        }
    }

    @Test
/**
 * 이 시나리오는 "lifecycle command defaults use UUID version seven" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `lifecycle command defaults use UUID version seven`() {
        AllocateVoucherCommand(
            tenantId = TENANT,
            campaignId = CAMPAIGN,
            reservationId = BATCH,
            canonicalUser = "user-1",
            expectedRevision = REVISION,
            idempotencyKey = IDEMPOTENCY_KEY,
        ).allocationId.version() shouldBeEqualTo 7

        ReplaceLostRevealCommand(
            tenantId = TENANT,
            campaignId = CAMPAIGN,
            allocationId = BATCH,
            canonicalUser = "user-1",
            expectedRevision = REVISION,
            idempotencyKey = IDEMPOTENCY_KEY,
        ).reservationId.version() shouldBeEqualTo 7

        ReserveVoucherCommand(
            tenantId = TENANT,
            campaignId = CAMPAIGN,
            canonicalUser = "user-1",
            idempotencyKey = IDEMPOTENCY_KEY,
        ).reservationId.version() shouldBeEqualTo 7
    }

    @Test
/**
 * 이 시나리오는 "import and generation commands reject invalid ordinals and empty work" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `import and generation commands reject invalid ordinals and empty work`() {
        assertFailsWith<IllegalArgumentException> {
            importChunk(firstOrdinal = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            importChunk(codes = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            GenerateChunkCommand(
                tenantId = TENANT,
                batchId = BATCH,
                campaignId = CAMPAIGN,
                firstOrdinal = -1,                  // not negative 
                manifestDigest = MANIFEST,
                count = 1,
                expectedRevision = REVISION,
                idempotencyKey = IDEMPOTENCY_KEY
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GenerateChunkCommand(
                tenantId = TENANT,
                batchId = BATCH,
                campaignId = CAMPAIGN,
                firstOrdinal = 0,
                manifestDigest = MANIFEST,
                count = 0,
                expectedRevision = REVISION,
                idempotencyKey = IDEMPOTENCY_KEY
            )
        }
        assertFailsWith<IllegalArgumentException> {
            importChunk(expectedRevision = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            GenerateChunkCommand(
                tenantId = TENANT,
                batchId = BATCH,
                campaignId = CAMPAIGN,
                firstOrdinal = 0,
                manifestDigest = MANIFEST,
                count = 1,
                expectedRevision = -1,                  // not negative
                idempotencyKey = IDEMPOTENCY_KEY
            )
        }
    }

    @Test
/**
 * 이 시나리오는 "known deterministic generation is rejected outside loopback test mode" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `known deterministic generation is rejected outside loopback test mode`() {
        val deterministic = object: GeneratedVoucherCodeSource {
            override val deterministic: Boolean = true
/**
 * nextCode은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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
        }.also { log.debug { it.message } }
    }

    @Test
/**
 * 이 시나리오는 "secure generation provides at least 128 bits without exposing a seed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `secure generation provides at least 128 bits without exposing a seed`() {
        val source = SecureRandomVoucherCodeSource()
        val generated = List(100) { source.nextCode() }

        source.deterministic.shouldBeFalse()
        generated.distinct() shouldHaveSize generated.size
        generated.all { it.startsWith("VP-") && it.removePrefix("VP-").length >= 22 }.shouldBeTrue()

        log.debug { "source=$source" }
        source.toString() shouldNotContainIgnoringCase "seed"
    }

    @Test
/**
 * 이 시나리오는 "constraint authority comes from nested PostgreSQL metadata and never exception text" 업무
 * 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
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
/**
 * 이 시나리오는 "retryable owner release survives bounded JDBC timeouts" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retryable owner release survives bounded JDBC timeouts`() {
        val attempts = AtomicInteger()

        releaseRetryableOwner {
            if (attempts.incrementAndGet() < 4) throw jdbcTimeout()
        }

        attempts.get() shouldBeEqualTo 4
    }

    @Test
/**
 * 이 시나리오는 "retryable owner release fails after its bounded retry budget" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `retryable owner release fails after its bounded retry budget`() {
        val attempts = AtomicInteger()

        assertFailsWith<VoucherPoolJdbcTimeoutException> {
            releaseRetryableOwner {
                attempts.incrementAndGet()
                throw jdbcTimeout()
            }
        }

        attempts.get() shouldBeEqualTo MAX_OWNER_RELEASE_ATTEMPTS // 8
    }

/**
 * importBatch은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun importBatch(expectedCount: Long): CreateImportBatchCommand =
        CreateImportBatchCommand(
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

/**
 * importChunk은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun importChunk(
        firstOrdinal: Long = 0,
        codes: List<String> = listOf("CODE-0"),
        expectedRevision: Long = REVISION,
    ): ImportChunkCommand =
        ImportChunkCommand(
            tenantId = TENANT,
            batchId = BATCH,
            campaignId = CAMPAIGN,
            firstOrdinal = firstOrdinal,
            manifestDigest = MANIFEST,
            codes = codes,
            expectedRevision = expectedRevision,
            idempotencyKey = IDEMPOTENCY_KEY
        )

/**
 * jdbcTimeout은 사전 생성 바우처 풀 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun jdbcTimeout(): VoucherPoolJdbcTimeoutException =
        VoucherPoolJdbcTimeoutException(
            JdbcExecutionLane.OPERATOR,
            JdbcTimeoutPhase.TRANSACTION,
            TimeoutException("owner release timeout"),
        )
}
