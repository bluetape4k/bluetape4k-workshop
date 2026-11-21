package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.codec.Base58
import io.bluetape4k.images.ocr.OcrEngine
import io.bluetape4k.images.ocr.TesseractOcrEngine
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
/**
 * NativeOcrEngineConfig은 이미지 처리 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class NativeOcrEngineConfig {

    @Bean
    @ConditionalOnExpression(
        $$"'${workshop.ocr.native-enabled:false}' == 'true' || '${ocr.enabled:false}' == 'true'",
    )
    /**
     * tesseractOcrEngine은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된
     * 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    fun tesseractOcrEngine(): OcrEngine =
        TesseractOcrEngine()

    /**
     * ocrEngineProvider은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된 응답으로
     * 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param ocrEngine 업무 처리에 필요한 ocrEngine 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun ocrEngineProvider(ocrEngine: ObjectProvider<OcrEngine>): OcrEngineProvider =
        OcrEngineProvider { ocrEngine.getIfAvailable() }

    /**
     * requestIdGenerator은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를
     * 계약된
     * 응답으로 매핑합니다.
     *
     * 호출 결과는 업무 상태와 감사·재시도 규칙을 보존한 뒤 다음 계층으로 전달해야 합니다.
     */
    @Bean
    fun requestIdGenerator(): RequestIdGenerator =
        RequestIdGenerator { "ocr-${Base58.randomString(8)}" }
}
