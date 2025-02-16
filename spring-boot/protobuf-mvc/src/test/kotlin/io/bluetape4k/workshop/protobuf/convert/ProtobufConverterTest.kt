package io.bluetape4k.workshop.protobuf.convert

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.protobuf.School.Course
import io.bluetape4k.workshop.protobuf.School.Student
import io.bluetape4k.workshop.protobuf.StudentKt.phoneNumber
import io.bluetape4k.workshop.protobuf.course
import io.bluetape4k.workshop.protobuf.student
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test

class ProtobufConverterTest {

    companion object: KLogging()

    private val course = course {
        id = 2
        courseName = "Spring Boot Programming"

        student.add(
            student {
                id = 3
                firstName = "Jane"
                lastName = "Doe"
                email = "jane.doe@example.com"

                phone.add(
                    phoneNumber {
                        number = "345678"
                        type = Student.PhoneType.LANDLINE
                    }
                )
                phone.add(
                    phoneNumber {
                        number = "456789"
                        type = Student.PhoneType.MOBILE
                    }
                )
            }
        )
    }

    /**
     * Json:
     * ```json
     * {
     *   "id": 2,
     *   "courseName": "Spring Boot Programming",
     *   "student": [{
     *     "id": 3,
     *     "firstName": "Jane",
     *     "lastName": "Doe",
     *     "email": "jane.doe@example.com",
     *     "phone": [{
     *       "number": "345678",
     *       "type": "LANDLINE"
     *     }, {
     *       "number": "456789"
     *     }]
     *   }]
     * }
     * ```
     *
     * Protobuf Message:
     * ```protobuf
     * fields {
     *   key: "courseName"
     *   value {
     *     string_value: "Spring Boot Programming"
     *   }
     * }
     * fields {
     *   key: "id"
     *   value {
     *     number_value: 2.0
     *   }
     * }
     * fields {
     *   key: "student"
     *   value {
     *     list_value {
     *       values {
     *         struct_value {
     *           fields {
     *             key: "email"
     *             value {
     *               string_value: "jane.doe@example.com"
     *             }
     *           }
     *           fields {
     *             key: "firstName"
     *             value {
     *               string_value: "Jane"
     *             }
     *           }
     *           fields {
     *             key: "id"
     *             value {
     *               number_value: 3.0
     *             }
     *           }
     *           fields {
     *             key: "lastName"
     *             value {
     *               string_value: "Doe"
     *             }
     *           }
     *           fields {
     *             key: "phone"
     *             value {
     *               list_value {
     *                 values {
     *                   struct_value {
     *                     fields {
     *                       key: "number"
     *                       value {
     *                         string_value: "345678"
     *                       }
     *                     }
     *                     fields {
     *                       key: "type"
     *                       value {
     *                         string_value: "LANDLINE"
     *                       }
     *                     }
     *                   }
     *                 }
     *                 values {
     *                   struct_value {
     *                     fields {
     *                       key: "number"
     *                       value {
     *                         string_value: "456789"
     *                       }
     *                     }
     *                   }
     *                 }
     *               }
     *             }
     *           }
     *         }
     *       }
     *     }
     *   }
     * }
     * ```
     */
    @Test
    fun `convert protobuf to json and parse as message`() {
        val json = course.toJson()
        log.debug { "json=$json" }
        json.shouldNotBeEmpty()

        val message = messageFromJson(json)
        log.debug { "message=$message" }
        message.toString().apply {
            this shouldContain "string_value: \"Spring Boot Programming\""
            this shouldContain "string_value: \"jane.doe@example.com\""
        }
    }

    @Test
    fun `convert protobuf to json and parse specific type`() {
        val json = course.toJson()
        log.debug { "json=\n$json" }
        json.shouldNotBeEmpty()

        val course2 = messageFromJsonOrNull<Course>(json)

        course2 shouldBeEqualTo course
    }
}
