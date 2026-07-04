package io.bluetape4k.workshop.cbor.course

import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import java.io.Serializable

data class Course(
    val id: Int,
    val name: String,
    val students: List<Student> = emptyList(),
) : Serializable {
    init {
        id.requirePositiveNumber("id")
        name.requireNotBlank("name")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class Student(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phones: List<Phone> = emptyList(),
) : Serializable {
    init {
        id.requirePositiveNumber("id")
        firstName.requireNotBlank("firstName")
        lastName.requireNotBlank("lastName")
        email.requireNotBlank("email")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

data class Phone(
    val number: String,
    val type: PhoneType,
) : Serializable {
    init {
        number.requireNotBlank("number")
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

enum class PhoneType {
    MOBILE,
    LANDLINE
}
