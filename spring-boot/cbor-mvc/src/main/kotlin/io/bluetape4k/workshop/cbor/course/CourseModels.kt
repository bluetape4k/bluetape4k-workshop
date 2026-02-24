package io.bluetape4k.workshop.cbor.course

import java.io.Serializable

data class Course(
    val id: Int,
    val name: String,
    val students: MutableList<Student> = mutableListOf(),
): Serializable

data class Student(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phones: MutableList<Phone> = mutableListOf(),
): Serializable

data class Phone(
    val number: String,
    val type: PhoneType,
): Serializable

enum class PhoneType {
    MOBILE,
    LANDLINE
}
