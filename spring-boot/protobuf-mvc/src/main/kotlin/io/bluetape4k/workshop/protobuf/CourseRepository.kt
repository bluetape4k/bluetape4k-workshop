package io.bluetape4k.workshop.protobuf

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.protobuf.School.Course

class CourseRepository(private val courses: Map<Int, Course>) {

    companion object : KLogging()

    fun getCourse(id: Int): Course {
        val courseId = id.requirePositiveNumber("id")
        return courses[courseId] ?: throw IllegalArgumentException("Course[$courseId] not found")
    }
}
