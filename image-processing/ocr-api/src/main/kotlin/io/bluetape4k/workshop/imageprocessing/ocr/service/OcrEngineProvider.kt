package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.images.ocr.OcrEngine

/**
 * Provides the native OCR engine when native OCR is enabled.
 */
fun interface OcrEngineProvider {
    fun get(): OcrEngine?
}
