package io.bluetape4k.workshop.exposed.r2dbc.domain

import java.io.Serializable

data class ErrorMessage(val message: String) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
