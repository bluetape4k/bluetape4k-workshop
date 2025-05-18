package io.bluetape4k.workshop.cbor.course

class CourseRepository(private val courses: MutableMap<Int, Course>) {

    fun getCourse(id: Int): Course {
        return courses[id] ?: throw IllegalArgumentException("Course[$id] not found")
    }
}
