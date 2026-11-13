package io.bluetape4k.workshop.commerce.voucherpool.domain

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.assertSoftly
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.io.serializer.BinarySerializationException
import io.bluetape4k.io.serializer.BinarySerializers
import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.SerializationFeature
import java.io.NotSerializableException
import java.io.Serializable
import java.util.stream.Stream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Suppress("DEPRECATION")
internal class VoucherPoolPoliciesTest {

    @ParameterizedTest
    @CsvSource(
        "DRAFT,ACTIVE,true",
        "ACTIVE,PAUSED,true",
        "PAUSED,ACTIVE,true",
        "REVOKING,ACTIVE,false",
        "REVOKING,REVOKED,true",
        "REVOKED,DRAFT,false",
    )
    fun `campaign transition matrix is closed`(
        from: CampaignState,
        to: CampaignState,
        allowed: Boolean,
    ) {
        CampaignPolicy.canTransition(from, to) shouldBeEqualTo allowed
    }

    @Test
    fun `campaign policy exposes only documented transitions`() {
        allowedTransitions(CampaignState.entries, CampaignPolicy::canTransition) shouldBeEqualTo
                setOf(
                    CampaignState.DRAFT to CampaignState.ACTIVE,
                    CampaignState.DRAFT to CampaignState.REVOKING,
                    CampaignState.ACTIVE to CampaignState.PAUSED,
                    CampaignState.ACTIVE to CampaignState.REVOKING,
                    CampaignState.PAUSED to CampaignState.ACTIVE,
                    CampaignState.PAUSED to CampaignState.REVOKING,
                    CampaignState.REVOKING to CampaignState.REVOKED,
                )
    }

    @ParameterizedTest
    @CsvSource(
        "STAGING,ACTIVE,true",
        "FAILED_RETRYABLE,STAGING,true",
        "ACTIVE,EXPIRING,true",
        "EXPIRING,EXPIRED,true",
        "FAILED_TERMINAL,REVOKING,true",
        "EXPIRED,ACTIVE,false",
    )
    fun `batch transition matrix follows checkpoint and terminal boundaries`(
        from: BatchState,
        to: BatchState,
        allowed: Boolean,
    ) {
        BatchPolicy.canTransition(from, to) shouldBeEqualTo allowed
    }

    @Test
    fun `batch policy exposes only documented transitions`() {
        allowedTransitions(BatchState.entries, BatchPolicy::canTransition) shouldBeEqualTo
                setOf(
                    BatchState.STAGING to BatchState.ACTIVE,
                    BatchState.STAGING to BatchState.FAILED_RETRYABLE,
                    BatchState.STAGING to BatchState.FAILED_TERMINAL,
                    BatchState.FAILED_RETRYABLE to BatchState.STAGING,
                    BatchState.FAILED_RETRYABLE to BatchState.REVOKING,
                    BatchState.FAILED_TERMINAL to BatchState.REVOKING,
                    BatchState.ACTIVE to BatchState.PAUSED,
                    BatchState.ACTIVE to BatchState.EXPIRING,
                    BatchState.ACTIVE to BatchState.REVOKING,
                    BatchState.PAUSED to BatchState.ACTIVE,
                    BatchState.PAUSED to BatchState.EXPIRING,
                    BatchState.PAUSED to BatchState.REVOKING,
                    BatchState.EXPIRING to BatchState.EXPIRED,
                    BatchState.REVOKING to BatchState.REVOKED,
                )
    }

    @ParameterizedTest
    @CsvSource(
        "AVAILABLE,RESERVED,true",
        "RESERVED,AVAILABLE,true",
        "RESERVED,ALLOCATED,true",
        "RESERVED,EXPIRED,true",
        "ALLOCATED,REDEEMED,true",
        "ALLOCATED,AVAILABLE,false",
        "REDEEMED,REVOKED,false",
    )
    fun `entry transition matrix prevents allocated code reuse`(
        from: EntryState,
        to: EntryState,
        allowed: Boolean,
    ) {
        EntryPolicy.canTransition(from, to) shouldBeEqualTo allowed
    }

    @Test
    fun `entry policy exposes only documented transitions`() {
        allowedTransitions(EntryState.entries, EntryPolicy::canTransition) shouldBeEqualTo
                setOf(
                    EntryState.AVAILABLE to EntryState.RESERVED,
                    EntryState.AVAILABLE to EntryState.REVOKED,
                    EntryState.AVAILABLE to EntryState.EXPIRED,
                    EntryState.RESERVED to EntryState.AVAILABLE,
                    EntryState.RESERVED to EntryState.ALLOCATED,
                    EntryState.RESERVED to EntryState.EXPIRED,
                    EntryState.RESERVED to EntryState.REVOKED,
                    EntryState.ALLOCATED to EntryState.REDEEMED,
                    EntryState.ALLOCATED to EntryState.RELEASED,
                    EntryState.ALLOCATED to EntryState.EXPIRED,
                    EntryState.ALLOCATED to EntryState.REVOKED,
                )
    }

    @ParameterizedTest
    @CsvSource(
        "ACTIVE,ALLOCATED,true",
        "ACTIVE,EXPIRED,true",
        "ACTIVE,RELEASED,true",
        "ACTIVE,REVOKED,true",
        "ALLOCATED,ACTIVE,false",
    )
    fun `reservation transition matrix closes terminal states`(
        from: ReservationState,
        to: ReservationState,
        allowed: Boolean,
    ) {
        ReservationPolicy.canTransition(from, to) shouldBeEqualTo allowed
    }

    @Test
    fun `reservation policy exposes only documented transitions`() {
        allowedTransitions(ReservationState.entries, ReservationPolicy::canTransition) shouldBeEqualTo
                setOf(
                    ReservationState.ACTIVE to ReservationState.ALLOCATED,
                    ReservationState.ACTIVE to ReservationState.EXPIRED,
                    ReservationState.ACTIVE to ReservationState.RELEASED,
                    ReservationState.ACTIVE to ReservationState.REVOKED,
                )
    }

    @Test
    fun `control characters and oversized voucher codes are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CanonicalVoucherCode("ABC\u0000DEF")
        }
        assertFailsWith<IllegalArgumentException> {
            CanonicalVoucherCode("A".repeat(257))
        }
    }

    @Test
    fun `unicode voucher codes are accepted without changing their value`() {
        val rawCode = "여름-할인-🎟️"
        val code = CanonicalVoucherCode(rawCode)

        code.withRawValue { it } shouldBeEqualTo rawCode
        code.toString() shouldBeEqualTo "CanonicalVoucherCode([REDACTED])"
    }

    @Test
    fun `supplementary unicode voucher codes honor the code point limit`() {
        val supplementaryCodePoint = "🎟"

        CanonicalVoucherCode.of(supplementaryCodePoint.repeat(256)) shouldBeEqualTo
                CanonicalVoucherCode.of(supplementaryCodePoint.repeat(256))

        assertFailsWith<IllegalArgumentException> {
            CanonicalVoucherCode.of(supplementaryCodePoint.repeat(257))
        }
    }

    @Test
    fun `blank and malformed unicode voucher codes are rejected`() {
        assertFailsWith<IllegalArgumentException> { CanonicalVoucherCode.of("   ") }
        assertFailsWith<IllegalArgumentException> { CanonicalVoucherCode.of("ABC\uD800DEF") }
        assertFailsWith<IllegalArgumentException> { CanonicalVoucherCode.of("ABC\uDC00DEF") }
    }

    @Test
    fun `voucher codes cannot be read or written through generic Java serialization`() {
        val code = CanonicalVoucherCode.of(RAW_CODE_SENTINEL)

        Serializable::class.java.isInstance(code).shouldBeFalse()
        CanonicalVoucherCode::class.java.methods
            .any { it.name == "getValue" && it.parameterCount == 0 }
            .shouldBeFalse()

        assertFailsWith<BinarySerializationException> {
            serialize(code)
        }
    }

    @Test
    fun `Jackson serialization never exposes a raw voucher code`() {
        val mapper =
            Jackson.defaultJsonMapper
                .rebuild()
                .apply { configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false) }
                .build()

        mapper.writeValueAsString(CanonicalVoucherCode(RAW_CODE_SENTINEL)) shouldNotContain RAW_CODE_SENTINEL
    }

    @Test
    fun `valid policy retains bounded lifetime values`() {
        val policy =
            VoucherPoolPolicy.of(
                perUserLimit = 1,
                reservationTtl = 15.minutes,
                allocationTtl = 1.hours,
                replacementAllowance = 1,
            )

        assertSoftly {
            add { policy.perUserLimit shouldBeEqualTo 1 }
            add { policy.reservationTtl shouldBeEqualTo 15.minutes }
            add { policy.allocationTtl shouldBeEqualTo 1.hours }
            add { policy.replacementAllowance shouldBeEqualTo 1 }
        }
    }

    @Test
    fun `valid policy survives Java serialization roundtrip`() {
        val policy = validPolicy()

        deserialize<VoucherPoolPolicy>(serialize(policy)) shouldBeEqualTo policy
    }

    @Test
    fun `Java deserialization rejects a policy whose serialized state bypasses the factory`() {
        val policy = validPolicy()
        val limitField = VoucherPoolPolicy::class.java.getDeclaredField("perUserLimit")
        limitField.trySetAccessible().shouldBeTrue()
        limitField.setInt(policy, 0)

        assertFailsWith<BinarySerializationException> {
            deserialize<VoucherPoolPolicy>(serialize(policy))
        }
    }

    @Test
    fun `non-positive limits and TTL values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            VoucherPoolPolicy(
                perUserLimit = 0,
                reservationTtl = 15.minutes,
                allocationTtl = 1.hours,
                replacementAllowance = 1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VoucherPoolPolicy(
                1,
                0.minutes,
                1.hours,
                1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VoucherPoolPolicy(
                perUserLimit = 1,
                reservationTtl = 15.minutes,
                allocationTtl = (-1).hours,
                replacementAllowance = 1
            )
        }
    }

    @Test
    fun `infinite TTL values are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            VoucherPoolPolicy(
                perUserLimit = 1,
                reservationTtl = Duration.INFINITE,
                allocationTtl = 1.hours,
                replacementAllowance = 1
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VoucherPoolPolicy(
                perUserLimit = 1,
                reservationTtl = 15.minutes,
                allocationTtl = Duration.INFINITE,
                replacementAllowance = 1
            )
        }
    }

    @ParameterizedTest
    @CsvSource("-1", "2")
    fun `replacement allowance cannot exceed the single lifetime recovery`(replacementAllowance: Int) {
        assertFailsWith<IllegalArgumentException> {
            VoucherPoolPolicy(
                perUserLimit = 1,
                reservationTtl = 15.minutes,
                allocationTtl = 1.hours,
                replacementAllowance = replacementAllowance
            )
        }
    }

    @ParameterizedTest(name = "{0} has stable error semantics")
    @MethodSource("errorCatalogCases")
    fun `error catalog closes every public error code`(case: ErrorCatalogCase) {
        val semantics = VoucherPoolErrorCatalog[case.code]
        val expected = case.semantics

        assertSoftly {
            add { semantics.httpStatus shouldBeEqualTo expected.httpStatus }
            add { semantics.retryable shouldBeEqualTo expected.retryable }
            add { semantics.descriptorAction shouldBeEqualTo expected.descriptorAction }
            add { semantics.tombstoneAction shouldBeEqualTo expected.tombstoneAction }
            add { semantics.callerRecovery shouldBeEqualTo expected.callerRecovery }
        }
    }

    @Test
    fun `error catalog contains exactly every public error code`() {
        VoucherPoolErrorCatalog.codes shouldBeEqualTo VoucherPoolErrorCode.entries.toSet()
    }

    @Test
    fun `expected error cases contain every public code exactly once`() {
        val expectedCodes = expectedErrorCases().map(ErrorCatalogCase::code)

        assertSoftly {
            add { expectedCodes.toSet() shouldBeEqualTo VoucherPoolErrorCode.entries.toSet() }
            add { expectedCodes shouldHaveSize expectedCodes.toSet().size }
        }
    }

    private fun <S> allowedTransitions(
        states: List<S>,
        canTransition: (S, S) -> Boolean,
    ): Set<Pair<S, S>> =
        states
            .flatMap { from -> states.map { to -> from to to } }
            .filter { (from, to) -> canTransition(from, to) }
            .toSet()

    private fun validPolicy(): VoucherPoolPolicy =
        VoucherPoolPolicy.of(
            perUserLimit = 1,
            reservationTtl = 15.minutes,
            allocationTtl = 1.hours,
            replacementAllowance = 1,
        )

    private fun serialize(value: Any): ByteArray =
        BinarySerializers.JdkUnfiltered.serialize(value)

    private inline fun <reified T: Any> deserialize(serialized: ByteArray): T =
        BinarySerializers.JdkUnfiltered.deserialize<T>(serialized).shouldNotBeNull()

    companion object: KLogging() {

        private const val RAW_CODE_SENTINEL = "raw-voucher-code-must-not-leak"

        @JvmStatic
        fun errorCatalogCases(): Stream<ErrorCatalogCase> = expectedErrorCases().stream()

        private fun expectedErrorCases(): List<ErrorCatalogCase> = buildList {
            addAll(commandCases())
            addAll(capacityCases())
            addAll(stateCases())
            addAll(resourceCases())
            addAll(protectionCases())
        }

        private fun commandCases(): List<ErrorCatalogCase> = listOf(
            case(
                VoucherPoolErrorCode.COMMAND_IN_PROGRESS,
                semantics(409, true, DescriptorAction.RELEASE, recovery = CallerRecovery.RETRY_AFTER),
            ),
            case(
                VoucherPoolErrorCode.IDEMPOTENCY_FINGERPRINT_CONFLICT,
                semantics(
                    409,
                    descriptor = DescriptorAction.NONE,
                    tombstone = TombstoneAction.RETAIN,
                    recovery = CallerRecovery.CHANGE_PAYLOAD_OR_KEY,
                ),
            ),
            case(
                VoucherPoolErrorCode.REPLAY_WINDOW_EXPIRED,
                semantics(
                    410,
                    descriptor = DescriptorAction.NONE,
                    tombstone = TombstoneAction.RETAIN,
                    recovery = CallerRecovery.LOOK_UP_EFFECT,
                ),
            ),
        )

        private fun capacityCases(): List<ErrorCatalogCase> = buildList {
            addAll(
                cases(
                    semantics(503, true, DescriptorAction.RELEASE, recovery = CallerRecovery.BOUNDED_BACKOFF),
                    VoucherPoolErrorCode.POOL_BUSY,
                    VoucherPoolErrorCode.BACKEND_TIMEOUT,
                    VoucherPoolErrorCode.BATCH_FAILED_RETRYABLE,
                )
            )
            addAll(
                cases(
                    terminal(CallerRecovery.TERMINAL_OR_OPERATOR_REVIEW),
                    VoucherPoolErrorCode.POOL_EXHAUSTED,
                    VoucherPoolErrorCode.USER_LIMIT_REACHED,
                )
            )
            add(
                case(
                    VoucherPoolErrorCode.STALE_REVISION,
                    semantics(
                        409,
                        descriptor = DescriptorAction.RELEASE,
                        recovery = CallerRecovery.REFRESH_SNAPSHOT,
                    ),
                )
            )
        }

        private fun stateCases(): List<ErrorCatalogCase> = buildList {
            addAll(
                cases(
                    semantics(
                        409,
                        true,
                        DescriptorAction.RELEASE,
                        recovery = CallerRecovery.REFRESH_STATE_WITH_BACKOFF,
                    ),
                    VoucherPoolErrorCode.CAMPAIGN_NOT_ACTIVE,
                    VoucherPoolErrorCode.CAMPAIGN_PAUSED,
                    VoucherPoolErrorCode.BATCH_PAUSED,
                    VoucherPoolErrorCode.BATCH_EXPIRING,
                )
            )
            addAll(
                cases(
                    terminal(CallerRecovery.USE_NEW_SCOPE_OR_OPERATOR_REVIEW),
                    VoucherPoolErrorCode.CAMPAIGN_REVOKING,
                    VoucherPoolErrorCode.CAMPAIGN_REVOKED,
                    VoucherPoolErrorCode.BATCH_REVOKED,
                    VoucherPoolErrorCode.BATCH_EXPIRED,
                    VoucherPoolErrorCode.BATCH_FAILED_TERMINAL,
                )
            )
        }

        private fun resourceCases(): List<ErrorCatalogCase> = buildList {
            addAll(
                cases(
                    terminal(CallerRecovery.CREATE_RESERVATION_OR_RECOVER),
                    VoucherPoolErrorCode.RESERVATION_EXPIRED,
                    VoucherPoolErrorCode.ALLOCATION_EXPIRED,
                )
            )
            addAll(
                cases(
                    semantics(
                        httpStatus = 404,
                        descriptor = DescriptorAction.RELEASE,
                        recovery = CallerRecovery.DO_NOT_EXPOSE_RESOURCE,
                    ),
                    VoucherPoolErrorCode.WRONG_OWNER,
                    VoucherPoolErrorCode.SCOPE_NOT_FOUND,
                )
            )
        }

        private fun protectionCases(): List<ErrorCatalogCase> = buildList {
            add(
                case(
                    VoucherPoolErrorCode.RATE_LIMITED,
                    this@Companion.semantics(
                        httpStatus = 429,
                        retryable = true,
                        descriptor = DescriptorAction.RELEASE,
                        recovery = CallerRecovery.RETRY_AFTER
                    ),
                ),
            )
            addAll(
                cases(
                    this@Companion.semantics(
                        503,
                        true,
                        DescriptorAction.RELEASE,
                        recovery = CallerRecovery.ESCALATE_FAIL_CLOSED,
                    ),
                    VoucherPoolErrorCode.KEY_MATERIAL_UNAVAILABLE,
                    VoucherPoolErrorCode.CIPHERTEXT_INVALID,
                )
            )
            add(
                case(
                    VoucherPoolErrorCode.ALREADY_REVEALED,
                    this@Companion.semantics(
                        200,
                        descriptor = DescriptorAction.STORE_SAFE,
                        recovery = CallerRecovery.USE_REPLACEMENT_FLOW,
                    ),
                )
            )
        }

        private fun terminal(recovery: CallerRecovery): VoucherPoolErrorSemantics =
            semantics(
                httpStatus = 409,
                descriptor = DescriptorAction.STORE,
                tombstone = TombstoneAction.STORE,
                recovery = recovery,
            )

        private fun semantics(
            httpStatus: Int,
            retryable: Boolean = false,
            descriptor: DescriptorAction,
            tombstone: TombstoneAction = TombstoneAction.NONE,
            recovery: CallerRecovery,
        ): VoucherPoolErrorSemantics =
            VoucherPoolErrorSemantics(
                httpStatus = httpStatus,
                retryable = retryable,
                descriptorAction = descriptor,
                tombstoneAction = tombstone,
                callerRecovery = recovery
            )

        private fun cases(
            semantics: VoucherPoolErrorSemantics,
            vararg codes: VoucherPoolErrorCode,
        ): List<ErrorCatalogCase> =
            codes.map { case(it, semantics) }

        private fun case(
            code: VoucherPoolErrorCode,
            semantics: VoucherPoolErrorSemantics,
        ): ErrorCatalogCase =
            ErrorCatalogCase(code, semantics)
    }
}

internal data class ErrorCatalogCase(
    val code: VoucherPoolErrorCode,
    val semantics: VoucherPoolErrorSemantics,
)
