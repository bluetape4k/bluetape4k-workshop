package io.bluetape4k.workshop.observability.basic.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.io.Serializable

/**
 * Inventory availability for a single item, returned by the downstream inventory service.
 *
 * ## Behavior / Contract
 * - Unknown JSON fields are ignored to tolerate downstream API additions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class Inventory(
    val itemId: Long,
    val available: Int,
) : Serializable {
    companion object {
        @JvmStatic
        private val serialVersionUID: Long = 1L
    }
}
