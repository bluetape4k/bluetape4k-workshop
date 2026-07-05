package io.bluetape4k.workshop.gateway.customer.model

import io.bluetape4k.support.requireNotBlank
import java.io.Serializable

data class Customer(
    val name: String,
): Serializable {
    init {
        name.requireNotBlank("name")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
