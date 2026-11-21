package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotNull

/**
 * 바코드 예제가 의도적으로 주입하는 입력 fixture입니다. 성공·무결과·손상
 * 입력을 구분해 decoder의 결과와 오류 경계를 재현합니다.
 * @property resource 이미지 처리 업무 흐름을 구성하는 값입니다. 명령 검증·영속화·이벤트 변환에서 의미를 보존합니다.
 */
internal enum class BarcodeExampleFixture(val resource: String) {
    /** 실제 QR 이미지를 읽어 decoder 성공 경로를 검증합니다. */
    /** 실제 QR 이미지를 읽어 decoder 성공 경로를 검증합니다. */
    SAMPLE("barcodes/qr.png"),
    /** 이미지 자체는 유효하지만 바코드가 없는 결과를 검증합니다. */
    /** 이미지 자체는 유효하지만 바코드가 없는 결과를 검증합니다. */
    NO_RESULT("barcodes/no-result.png"),
    /** 이미지 포맷/바이트가 손상된 입력을 검증해 예외를 업무 오류로 매핑합니다. */
    /** 이미지 포맷/바이트가 손상된 입력을 검증해 예외를 업무 오류로 매핑합니다. */
    MALFORMED("barcodes/malformed.bin"),
}

/**
 * BarcodeExampleFixtures은 이미지 처리 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
internal class BarcodeExampleFixtures internal constructor(
    resourceLoader: (String) -> ByteArray? = ::loadClasspathResource,
) {
    private companion object: KLogging()
    /**
     * 이미지 처리 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */

    private val resources: Map<BarcodeExampleFixture, ByteArray> =
        BarcodeExampleFixture.entries.associateWith { fixture ->
            resourceLoader(fixture.resource).requireNotNull {
                "Required barcode example fixture is missing: ${fixture.resource}"
            }.copyOf()
        }

    /**
     * bytes은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param fixture 업무 처리에 필요한 fixture 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    fun bytes(fixture: BarcodeExampleFixture): ByteArray = resources.getValue(fixture).copyOf()
}

/**
 * loadClasspathResource은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
 * 계약된
 * 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param resource 업무 처리에 필요한 resource 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
private fun loadClasspathResource(resource: String): ByteArray? =
    BarcodeExampleFixtures::class.java.classLoader
        .getResourceAsStream(resource)
        ?.use { it.readBytes() }
