package io.bluetape4k.workshop.protobuf

import io.bluetape4k.workshop.protobuf.School.Course

class CourseRepository(private val courses: MutableMap<Int, Course>) {

    fun getCourse(id: Int): Course {
        return courses[id] ?: throw IllegalArgumentException("Course not found")
    }
}
