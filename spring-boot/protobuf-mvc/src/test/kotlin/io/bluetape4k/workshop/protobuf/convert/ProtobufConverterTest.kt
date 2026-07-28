package io.bluetape4k.workshop.protobuf.convert

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.protobuf.School.Course
import io.bluetape4k.workshop.protobuf.School.Student
import io.bluetape4k.workshop.protobuf.StudentKt.phoneNumber
import io.bluetape4k.workshop.protobuf.course
import io.bluetape4k.workshop.protobuf.student
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import org.junit.jupiter.api.Test

class ProtobufConverterTest {

    companion object : KLoggingChannel()

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
     * JSON 입력 예제입니다.
     *
     * 아래 블록은 변환 대상이 되는 원본 JSON 문서 구조를 그대로 보여줍니다.
     * 필드 이름과 문자열 값은 Protobuf 스키마, 테스트 픽스처, 직렬화 규칙을
     * 검증하는 코드-facing 예제이므로 원문 식별자를 유지합니다.
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
     * 변환된 Protobuf Message 예제입니다.
     *
     * 아래 블록은 `Struct` 형태로 해석된 Protobuf 메시지의 대표 출력입니다.
     * `fields`, `key`, `value`, `string_value`, `number_value` 같은 토큰은
     * Protobuf 텍스트 포맷에서 정한 필드명이므로 번역하지 않습니다.
     * 이 예제는 JSON 필드가 중첩 리스트와 구조체로 보존되는지 확인하는 기준입니다.
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
