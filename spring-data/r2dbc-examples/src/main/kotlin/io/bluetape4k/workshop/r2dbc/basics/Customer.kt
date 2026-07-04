package io.bluetape4k.workshop.r2dbc.basics

import org.springframework.data.annotation.Id
import java.io.Serializable

data class Customer(
    val firstname: String,
    val lastname: String,
    @Id
    var id: Long? = null,
) : Serializable {
    val hasId: Boolean get() = id != null

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
