package io.bluetape4k.workshop.chaos.model

import java.io.Serializable

data class Student(
    val id: Int? = null,
    val name: String? = null,
    val passportNumber: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
