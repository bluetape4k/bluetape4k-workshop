package io.bluetape4k.workshop.webflux.model

import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant

class Command : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class Event(
    val id: String,
    val data: List<Quote>,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class Quote(
    val ticker: String,
    val price: BigDecimal,
    val instant: Instant = Instant.now(),
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
