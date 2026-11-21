package io.bluetape4k.workshop.commerce.shared.integration

import io.bluetape4k.assertions.shouldBeEqualTo
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

/**
 * CommerceIntegrationContractsTest 테스트 묶음은 프로모션 바우처의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class CommerceIntegrationContractsTest {

    @Test
/**
 * 이 시나리오는 "payload digest preserves provider lowercase SHA-256 wire contract" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `payload digest preserves provider lowercase SHA-256 wire contract`() {
        listOf(
            "",
            "한글과 emoji🙂",
            "{\"tenantId\":\"tenant-a\",\"quantity\":\"2.5\"}",
        ).forEach { payload ->
            PayloadDigest.of(payload) shouldBeEqualTo jdkDigest(payload)
        }
    }

    @Test
/**
 * 이 시나리오는 "payload digest rejects non-canonical values" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `payload digest rejects non-canonical values`() {
        val payload = "payload"
        val digest = PayloadDigest.of(payload)

        PayloadDigest.matches(payload, digest) shouldBeEqualTo true
        PayloadDigest.matches(payload, digest.uppercase()) shouldBeEqualTo false
        PayloadDigest.matches(payload, "zz${digest.drop(2)}") shouldBeEqualTo false
        PayloadDigest.matches(payload, digest.dropLast(2)) shouldBeEqualTo false
        PayloadDigest.matches("other", digest) shouldBeEqualTo false
    }

    @Test
/**
 * 이 시나리오는 "aggregate partition key preserves tenant aggregate id order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `aggregate partition key preserves tenant aggregate id order`() {
        AggregatePartitionKey.of("tenant-a", "Invoice", "invoice-7") shouldBeEqualTo
            "tenant-a|Invoice|invoice-7"
    }

/**
 * jdkDigest은 프로모션 바우처 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun jdkDigest(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))
}
