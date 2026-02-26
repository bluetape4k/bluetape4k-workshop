package io.bluetape4k.workshop.coroutines.model

import io.bluetape4k.codec.Base58
import java.io.Serializable

/**
 * Banner DTO
 */
data class Banner(
    val title: String,
    val message: String,
): Serializable {

    companion object {
        val TEST_BANNER = Banner("애국가", "동해물과 백두산이 마르고 닳도록".repeat(4) + Base58.randomString(256))
    }
}
