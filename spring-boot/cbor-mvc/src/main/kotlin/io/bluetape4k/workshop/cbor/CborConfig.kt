package io.bluetape4k.workshop.cbor

import io.bluetape4k.workshop.cbor.course.Course
import io.bluetape4k.workshop.cbor.course.CourseRepository
import io.bluetape4k.workshop.cbor.course.Phone
import io.bluetape4k.workshop.cbor.course.PhoneType
import io.bluetape4k.workshop.cbor.course.Student
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.cbor.MappingJackson2CborHttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class CborConfig: WebMvcConfigurer {

    @Bean
    @Profile("cbor")
    fun cborHttpMessageConverter(): MappingJackson2CborHttpMessageConverter {
        return MappingJackson2CborHttpMessageConverter()
    }

    override fun configureMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters.add(cborHttpMessageConverter())
    }

    @Bean
    fun courseRepository(): CourseRepository {
        val courses = mutableMapOf(
            1 to Course(
                id = 1,
                name = "Kotlin Programming",
                students = createTestStudents()

            ),
            2 to Course(
                id = 2,
                name = "Spring Boot Programming"
            )
        )
        return CourseRepository(courses)
    }

    private fun createTestStudents(): MutableList<Student> {
        val student1 = Student(
            id = 1,
            firstName = "John",
            lastName = "Doe",
            email = "john.doe@example.com",
            phones = mutableListOf(
                Phone(
                    number = "010-1234-5678",
                    type = PhoneType.MOBILE
                )
            )
        )

        val student2 = Student(
            id = 2,
            firstName = "Richard",
            lastName = "Roe",
            email = "richard.roe@example.com",
            phones = mutableListOf(
                Phone(
                    number = "234567",
                    type = PhoneType.LANDLINE
                )
            )
        )

        val student3 = Student(
            id = 3,
            firstName = "Jane",
            lastName = "Doe",
            email = "jane.doe@example.com",
            phones = mutableListOf(
                Phone(
                    number = "345678",
                    type = PhoneType.LANDLINE
                ),
                Phone(
                    number = "456789",
                    type = PhoneType.LANDLINE
                )
            )
        )

        return mutableListOf(student1, student2, student3)
    }
}
