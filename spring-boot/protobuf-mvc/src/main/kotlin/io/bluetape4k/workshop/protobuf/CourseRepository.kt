package io.bluetape4k.workshop.protobuf

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.protobuf.School.Course

class CourseRepository(private val courses: MutableMap<Int, Course>) {

    companion object: KLogging()

    fun getCourse(id: Int): Course {
        return courses[id] ?: throw IllegalArgumentException("Course[$id] not found")
    }
}
