package io.bluetape4k.workshop.commerce.voucher.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.util.UUID

internal class VoucherCodeServiceTest {
    private val service = VoucherCodeService(fixedKeyRing())

    @Test
    fun `generated code has version payload checksum and a separate storage verifier`() {
        val issued = service.issue(fixedGenerationInput())

        assertTrue(issued.code.matches(Regex("V7-[1-9A-HJ-NP-Za-km-z]{22}[1-9A-HJ-NP-Za-km-z]{2}")))
        service.verify(issued.code, issued.verifier, verificationKeyVersion = 7).shouldBeTrue()
        issued.verifier.contentEquals(issued.code.toByteArray()).shouldBeFalse()
        issued.generationKeyVersion shouldBeEqualTo 5
        issued.verificationKeyVersion shouldBeEqualTo 7
    }

    @Test
    fun `fixed input has a stable golden vector`() {
        val issued = service.issue(fixedGenerationInput())

        issued.code shouldBeEqualTo "V7-2JNj1cRbsKnoHUCJNpSTTmjR"
        issued.verifier.toHexString() shouldBeEqualTo
            "90e3bbf91f398b35d6e209fe13fa222f44137d142a54b4a0811ac896e2157689"
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "V7-한글",
            "V7-000000000000000000000000",
            "V999-abc",
            "V7-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "V7-2JNj1cRbsKnoHUCJNpSTTmjX",
            "V6-2JNj1cRbsKnoHUCJNpSTTmjR",
        ],
    )
    fun `invalid code forms fail with one redacted result`(candidate: String) {
        val captured = captureLogs { service.verifyExternal(candidate) shouldBeEqualTo VerificationResult.INVALID_CODE }

        if (candidate.isNotEmpty()) assertFalse(captured.contains(candidate))
    }

    @Test
    fun `tenant campaign and allocation are separated generation inputs`() {
        val input = fixedGenerationInput()
        val baseline = service.issue(input)

        assertNotEquals(baseline.code, service.issue(input.copy(tenantId = "tenant-b")).code)
        assertNotEquals(
            baseline.code,
            service.issue(input.copy(campaignId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ac"))).code,
        )
        assertNotEquals(
            baseline.code,
            service.issue(input.copy(allocationId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ce"))).code,
        )
    }

    @Test
    fun `active old verification key supports rotation and retirement fails closed`() {
        val issued = service.issue(fixedGenerationInput())
        val rotatedRing =
            fixedKeyRing().copy(
                currentVerificationVersion = 8,
                verificationKeys =
                    fixedKeyRing().verificationKeys +
                        (8 to "verification-secret-material-00000002".toByteArray()),
            )
        val rotated = VoucherCodeService(rotatedRing)
        val retired =
            VoucherCodeService(
                rotatedRing.copy(verificationKeys = rotatedRing.verificationKeys - 7),
            )

        rotated.verify(issued.code, issued.verifier, verificationKeyVersion = 7).shouldBeTrue()
        retired.verify(issued.code, issued.verifier, verificationKeyVersion = 7).shouldBeFalse()
        retired.verifyExternal(issued.code) shouldBeEqualTo VerificationResult.INVALID_CODE
    }

    @Test
    fun `wrong verifier is rejected with constant time comparison`() {
        val issued = service.issue(fixedGenerationInput())

        service.verify(issued.code, ByteArray(32) { 0x01 }, verificationKeyVersion = 7).shouldBeFalse()
    }

    private fun captureLogs(block: () -> Unit): String {
        val logger = LoggerFactory.getLogger(VoucherCodeService::class.java) as Logger
        val originalLevel = logger.level
        val appender = ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        return try {
            block()
            appender.list.joinToString("\n") { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = originalLevel
        }
    }

    private fun fixedKeyRing(): VoucherCodeKeyRing =
        VoucherCodeKeyRing(
            currentGenerationVersion = 5,
            currentVerificationVersion = 7,
            generationKeys = mapOf(5 to "generation-secret-material-0000000001".toByteArray()),
            verificationKeys = mapOf(7 to "verification-secret-material-00000001".toByteArray()),
        )

    private fun fixedGenerationInput(): VoucherGenerationInput =
        VoucherGenerationInput(
            tenantId = "tenant-a",
            campaignId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab"),
            allocationId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890cd"),
        )
}
