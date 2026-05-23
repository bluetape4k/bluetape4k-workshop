package io.bluetape4k.workshop.lock.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.io.Serializable

/**
 * Represents an inventory item managed by the distributed lock workshop.
 *
 * ## Behavior / Contract
 * - [id] must be positive; [name] must be non-blank; [initialStock] must be >= 0.
 * - Equality and hashing are based on [id], [name], and [initialStock].
 */
data class Inventory(
    val id: Long,
    val name: String,
    val initialStock: Int,
) : Serializable {

    init {
        id.requirePositiveNumber("id")
        name.requireNotBlank("name")
        initialStock.requireZeroOrPositiveNumber("initialStock")
    }

    companion object : KLogging() {
        private const val serialVersionUID = 1L
    }
}
