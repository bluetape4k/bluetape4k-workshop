package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.images.ocr.OcrEngine
import io.bluetape4k.images.ocr.TesseractOcrEngine
import java.util.UUID
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class NativeOcrEngineConfig {

    @Bean
    @ConditionalOnExpression(
        "'\${workshop.ocr.native-enabled:false}' == 'true' || '\${ocr.enabled:false}' == 'true'",
    )
    fun tesseractOcrEngine(): OcrEngine =
        TesseractOcrEngine()

    @Bean
    fun ocrEngineProvider(ocrEngine: ObjectProvider<OcrEngine>): OcrEngineProvider =
        OcrEngineProvider { ocrEngine.getIfAvailable() }

    @Bean
    fun requestIdGenerator(): RequestIdGenerator =
        RequestIdGenerator { "ocr-${UUID.randomUUID()}" }
}
