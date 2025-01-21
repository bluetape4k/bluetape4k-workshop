package io.bluetape4k.workshop.cbor.course

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping(produces = [MediaType.APPLICATION_CBOR_VALUE])
class CourseController(private val courseRepository: CourseRepository) {

    @RequestMapping("/courses/{id}")
    fun course(@PathVariable id: Int): Course {
        return courseRepository.getCourse(id)
    }
}
