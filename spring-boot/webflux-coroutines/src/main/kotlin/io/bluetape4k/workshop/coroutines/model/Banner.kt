package io.bluetape4k.workshop.coroutines.model

import java.io.Serializable

/**
 * Banner DTO
 */
data class Banner(
    val title: String,
    val message: String,
): Serializable {

    companion object {
        val TEST_BANNER = Banner("제목", "동해물과 백두산이 마르고 닳도록".repeat(10))
    }
}
