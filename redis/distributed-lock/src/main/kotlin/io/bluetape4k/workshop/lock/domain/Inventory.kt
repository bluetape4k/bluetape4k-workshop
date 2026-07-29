package io.bluetape4k.workshop.lock.domain

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.support.requireZeroOrPositiveNumber
import java.io.Serializable

/**
 * distributed lock workshop 이 관리하는 inventory item 을 표현합니다.
 *
 * ## Behavior / Contract
 * - [id] 는 positive 여야 하고, [name] 은 non-blank 여야 하며, [initialStock] 은 0 이상이어야 합니다.
 * - equality 와 hashing 은 [id], [name], [initialStock] 기준입니다.
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
