package io.bluetape4k.workshop.protobuf

import io.bluetape4k.workshop.protobuf.School.Course
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class CourseController(private val courseRepository: CourseRepository) {

    @RequestMapping("/courses/{id}")
    fun course(@PathVariable id: Int): Course {
        return courseRepository.getCourse(id)
    }
}
