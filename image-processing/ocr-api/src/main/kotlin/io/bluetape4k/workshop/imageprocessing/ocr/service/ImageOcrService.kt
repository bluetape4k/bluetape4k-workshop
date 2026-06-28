package io.bluetape4k.workshop.imageprocessing.ocr.service

import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrRequest
import io.bluetape4k.workshop.imageprocessing.ocr.model.ImageOcrResponse

/**
 * Recognizes text from uploaded images for the OCR workshop API.
 */
interface ImageOcrService {

    /**
     * Recognizes text or returns a structured fallback response.
     */
    suspend fun recognize(request: ImageOcrRequest): ImageOcrResponse
}

/**
 * Supplies request IDs so tests can use deterministic IDs.
 */
fun interface RequestIdGenerator {
    fun nextId(): String
}
