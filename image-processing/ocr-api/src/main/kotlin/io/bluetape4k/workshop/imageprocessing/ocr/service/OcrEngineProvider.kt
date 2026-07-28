package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.images.ocr.OcrEngine

/**
 * native OCR이 활성화되었을 때 native OCR 엔진을 제공합니다.
 */
fun interface OcrEngineProvider {
    fun get(): OcrEngine?
}
