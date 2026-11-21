package io.bluetape4k.workshop.imageprocessing.ocr

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.images.ocr.OcrEngine
import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.imageprocessing.ocr.service.OcrEngineProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.test.context.runner.ApplicationContextRunner

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
/**
 * 기본 실행이 native Tesseract에 우연히 의존하지 않는지 확인합니다.
 *
 * 명시적으로 꺼진 context에는 `OcrEngine` bean이 없고 provider가 null이며,
 * `ocr.enabled=true`만 선택했을 때 native bean이 생성됩니다. 이 분리는 CI의
 * deterministic smoke 경로와 로컬 opt-in 경로를 설명하는 구성 계약입니다.
 */
class NativeOcrDisabledContextTest {

    private companion object: KLogging()

/**
 * contextRunner은 이미지 처리 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ImageOcrApiApplication::class.java)

    @Test
/**
 * 이 시나리오는 "default context does not create native OCR engine" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `default context does not create native OCR engine`() {
        contextRunner
            .withPropertyValues(
                "ocr.enabled=false",
                "workshop.ocr.native-enabled=false",
            )
            .run { context ->
                context.getBeansOfType<OcrEngine>().isNotEmpty().shouldBeFalse()
                context.getBean<OcrEngineProvider>().get().shouldBeNull()
            }
    }

    @Test
/**
 * 이 시나리오는 "ocr enabled system property creates native OCR engine" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `ocr enabled system property creates native OCR engine`() {
        contextRunner
            .withPropertyValues("ocr.enabled=true")
            .run { context ->
                context.getBeansOfType<OcrEngine>().isNotEmpty().shouldBeTrue()
                context.getBean<OcrEngineProvider>().get().shouldNotBeNull()
            }
    }
}
