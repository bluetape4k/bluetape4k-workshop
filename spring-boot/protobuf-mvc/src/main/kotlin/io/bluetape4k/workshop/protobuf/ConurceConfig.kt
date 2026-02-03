package io.bluetape4k.workshop.protobuf

import io.bluetape4k.collections.eclipse.fastListOf
import io.bluetape4k.collections.eclipse.unifiedMapOf
import io.bluetape4k.workshop.protobuf.School.Student
import io.bluetape4k.workshop.protobuf.StudentKt.phoneNumber
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter

@Configuration
class ConurceConfig {

    @Bean
    fun protobufHttpMessageConverter(): ProtobufHttpMessageConverter {
        return ProtobufHttpMessageConverter()
    }

    @Bean
    fun courceRepository(): CourseRepository {
        val courses = unifiedMapOf(
            1 to course {
                id = 1
                courseName = "Kotlin Programming"
                student.addAll(createTestStudents())
            },
            2 to course {
                id = 2
                courseName = "Spring Boot Programming"
            }
        )
        return CourseRepository(courses)
    }

    private fun createTestStudents(): List<Student> {
        val phone1 = phoneNumber {
            number = "010-1234-5678"
            type = Student.PhoneType.MOBILE
        }
        val student1 = student {
            id = 1
            firstName = "John"
            lastName = "Doe"
            email = "john.doe@example.com"
            phone.add(phone1)
        }

        val student2 = student {
            id = 2
            firstName = "Richard"
            lastName = "Roe"
            email = "richard.roe@example.com"
            phoneNumber {
                number = "234567"
                type = Student.PhoneType.LANDLINE
            }
        }
        val student3 = student {
            id = 3
            firstName = "Jane"
            lastName = "Doe"
            email = "jane.doe@example.com"
            phoneNumber {
                number = "345678"
                type = Student.PhoneType.LANDLINE
            }
            phoneNumber {
                number = "456789"
                type = Student.PhoneType.MOBILE
            }
        }

        return fastListOf(student1, student2, student3)
    }
}
