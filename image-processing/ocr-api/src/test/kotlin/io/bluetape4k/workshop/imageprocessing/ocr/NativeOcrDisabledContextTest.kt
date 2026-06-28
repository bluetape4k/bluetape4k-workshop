package io.bluetape4k.workshop.imageprocessing.ocr

import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.images.ocr.OcrEngine
import io.bluetape4k.workshop.imageprocessing.ocr.service.OcrEngineProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.test.context.runner.ApplicationContextRunner

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NativeOcrDisabledContextTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ImageOcrApiApplication::class.java)

    @Test
    fun `default context does not create native OCR engine`() {
        contextRunner
            .withPropertyValues(
                "ocr.enabled=false",
                "workshop.ocr.native-enabled=false",
            )
            .run { context ->
                context.getBeansOfType<OcrEngine>().isNotEmpty().shouldBeFalse()
                context.getBean(OcrEngineProvider::class.java).get().shouldBeNull()
            }
    }

    @Test
    fun `ocr enabled system property creates native OCR engine`() {
        contextRunner
            .withPropertyValues("ocr.enabled=true")
            .run { context ->
                context.getBeansOfType<OcrEngine>().isNotEmpty().shouldBeTrue()
                context.getBean(OcrEngineProvider::class.java).get().shouldNotBeNull()
            }
    }
}
