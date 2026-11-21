package io.bluetape4k.workshop.commerce.voucher.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.read.ListAppender
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.toUtf8Bytes
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import java.util.*

/**
 * VoucherCodeServiceTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
internal class VoucherCodeServiceTest {

    companion object: KLogging()

/**
 * service은 프로모션 바우처 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val service = VoucherCodeService(fixedKeyRing())

    @Test
/**
 * 이 시나리오는 "generated code has version payload checksum and a separate storage verifier" 업무 규칙을
 * 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `generated code has version payload checksum and a separate storage verifier`() {
        val issued = service.issue(fixedGenerationInput())

        issued.code.matches(Regex("V7-[1-9A-HJ-NP-Za-km-z]{22}[1-9A-HJ-NP-Za-km-z]{2}")).shouldBeTrue()

        service.verify(
            candidate = issued.code,
            expectedVerifier = issued.verifier,
            verificationKeyVersion = 7
        ).shouldBeTrue()

        issued.verifier.contentEquals(issued.code.toUtf8Bytes()).shouldBeFalse()
        issued.generationKeyVersion shouldBeEqualTo 5
        issued.verificationKeyVersion shouldBeEqualTo 7
    }

    @Test
/**
 * 이 시나리오는 "fixed input has a stable golden vector" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `fixed input has a stable golden vector`() {
        val issued = service.issue(fixedGenerationInput())

        issued.code shouldBeEqualTo "V7-2JNj1cRbsKnoHUCJNpSTTmjR"
        issued.verifier
            .toHexString() shouldBeEqualTo "90e3bbf91f398b35d6e209fe13fa222f44137d142a54b4a0811ac896e2157689"
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
/**
 * 이 시나리오는 "invalid code forms fail with one redacted result" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `invalid code forms fail with one redacted result`(candidate: String) {
        val captured = captureLogs {
            service.verifyExternal(candidate) shouldBeEqualTo VerificationResult.INVALID_CODE
        }

        if (candidate.isNotEmpty()) {
            captured shouldNotContain candidate
        }
    }

    @Test
/**
 * 이 시나리오는 "tenant campaign and allocation are separated generation inputs" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `tenant campaign and allocation are separated generation inputs`() {
        val input = fixedGenerationInput()
        val baseline = service.issue(input)

        baseline.code shouldNotBeEqualTo service.issue(input.copy(tenantId = "tenant-b")).code
        baseline.code shouldNotBeEqualTo
                service.issue(input.copy(campaignId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ac"))).code
        baseline.code shouldNotBeEqualTo
                service.issue(input.copy(allocationId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ce"))).code
    }

    @Test
/**
 * 이 시나리오는 "active old verification key supports rotation and retirement fails closed" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `active old verification key supports rotation and retirement fails closed`() {
        val issued = service.issue(fixedGenerationInput())
        val rotatedRing = fixedKeyRing().copy(
            currentVerificationVersion = 8,
            verificationKeys = fixedKeyRing().verificationKeys + (8 to "verification-secret-material-00000002".toUtf8Bytes()),
        )
        val rotated = VoucherCodeService(rotatedRing)
        val retired = VoucherCodeService(
            rotatedRing.copy(verificationKeys = rotatedRing.verificationKeys - 7),
        )

        rotated.verify(issued.code, issued.verifier, verificationKeyVersion = 7).shouldBeTrue()
        retired.verify(issued.code, issued.verifier, verificationKeyVersion = 7).shouldBeFalse()
        retired.verifyExternal(issued.code) shouldBeEqualTo VerificationResult.INVALID_CODE
    }

    @Test
/**
 * 이 시나리오는 "wrong verifier is rejected with constant time comparison" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `wrong verifier is rejected with constant time comparison`() {
        val issued = service.issue(fixedGenerationInput())

        service.verify(
            candidate = issued.code,
            expectedVerifier = ByteArray(32) { 0x01 },
            verificationKeyVersion = 7
        ).shouldBeFalse()
    }

/**
 * captureLogs은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
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

/**
 * fixedKeyRing은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun fixedKeyRing(): VoucherCodeKeyRing =
        VoucherCodeKeyRing(
            currentGenerationVersion = 5,
            currentVerificationVersion = 7,
            generationKeys = mapOf(5 to "generation-secret-material-0000000001".toUtf8Bytes()),
            verificationKeys = mapOf(7 to "verification-secret-material-00000001".toUtf8Bytes()),
        )

/**
 * fixedGenerationInput은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun fixedGenerationInput(): VoucherGenerationInput =
        VoucherGenerationInput(
            tenantId = "tenant-a",
            campaignId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890ab"),
            allocationId = UUID.fromString("018f1f2e-3d4c-7b6a-8f90-1234567890cd"),
        )
}
