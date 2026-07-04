package io.bluetape4k.workshop.cbor.course

import io.bluetape4k.support.requirePositiveNumber

class CourseRepository(private val courses: Map<Int, Course>) {

    fun getCourse(id: Int): Course {
        val courseId = id.requirePositiveNumber("id")
        return courses[courseId] ?: throw IllegalArgumentException("Course[$courseId] not found")
    }
}
