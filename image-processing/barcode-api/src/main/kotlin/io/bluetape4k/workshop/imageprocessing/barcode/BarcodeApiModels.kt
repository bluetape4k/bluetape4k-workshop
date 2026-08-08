package io.bluetape4k.workshop.imageprocessing.barcode

import io.bluetape4k.images.barcode.BarcodeFormat
import io.bluetape4k.support.requireNotBlank
import org.springframework.http.HttpStatus
import java.io.Serializable

internal data class BarcodeExtractionResponse(
    val count: Int,
    val results: List<BarcodeResultResponse>,
) : Serializable {

    init {
        require(count == results.size) { "count must match results.size" }
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class BarcodeResultResponse(
    val text: String,
    val format: BarcodeFormat,
    val provider: String,
) : Serializable {

    init {
        text.requireNotBlank("text")
        provider.requireNotBlank("provider")
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal data class BarcodeErrorResponse(
    val error: String,
    val reason: String? = null,
    val message: String,
) : Serializable {

    init {
        error.requireNotBlank("error")
        reason?.requireNotBlank("reason")
        message.requireNotBlank("message")
    }

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}

internal class BarcodeRequestException(
    val status: HttpStatus,
    val error: String,
    message: String,
) : RuntimeException(message)
