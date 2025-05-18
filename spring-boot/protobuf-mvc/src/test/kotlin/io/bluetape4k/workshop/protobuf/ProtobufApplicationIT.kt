package io.bluetape4k.workshop.protobuf

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.protobuf.School.Course
import io.bluetape4k.workshop.protobuf.convert.toJson
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEmpty
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClients
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.converter.protobuf.ProtobufHttpMessageConverter
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.getForEntity
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.io.InputStream

@SpringBootTest(
    classes = [ProtobufApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ProtobufApplicationIT {

    companion object: KLoggingChannel()

    private val restTemplate: RestTemplate by lazy {
        RestTemplate(listOf(ProtobufHttpMessageConverter()))
    }

    @LocalServerPort
    private var port: Int = 8080

    private val baseUrl: String get() = "http://localhost:$port"

    private val client: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .build()
    }

    private val testClient: WebTestClient by lazy {
        WebTestClient.bindToServer()
            .baseUrl(baseUrl)
            .build()
    }

    @Test
    fun `context loading`() {
        restTemplate.shouldNotBeNull()
        testClient.shouldNotBeNull()
    }

    @Test
    fun `using restTemplate`() {
        val course1 = restTemplate.getForEntity<Course>("$baseUrl/courses/1").body
        course1.shouldNotBeNull()
        assertCourse1(course1)
    }

    @Test
    fun `using HttpClient`() {
        val request = HttpGet("$baseUrl/courses/1")
        val protobufStream = executeHttpRequest(request)

        val course1 = Course.parseFrom(protobufStream)
        log.debug { "course1=$course1" }
        assertCourse1(course1)
    }

    private fun executeHttpRequest(request: HttpGet): InputStream {
        val httpClient = HttpClients.createDefault()
        val httpResponse = httpClient.execute(request)
        return httpResponse.entity.content
    }

    @Test
    fun `using WebClient`() = runSuspendIO {
        val course1 = client.get()
            .uri("/courses/1")
            .accept(MediaType.APPLICATION_PROTOBUF)
            .retrieve()
            .awaitBody<Course>()

        log.debug { "course1=$course1" }
        assertCourse1(course1)
    }

    @Test
    fun `using WebTestClient`() = runSuspendIO {
        val bytes = testClient.get()
            .uri("/courses/1")
            .accept(MediaType.APPLICATION_PROTOBUF)
            .exchange()
            .expectStatus().isOk
            .returnResult<ByteArray>().responseBody
            .awaitSingle()

        val course1 = Course.parseFrom(bytes)

        log.debug { "course1=$course1" }
        assertCourse1(course1)
    }

    private fun assertCourse1(course: Course) {
        course.id shouldBeEqualTo 1
        course.courseName shouldBeEqualTo "Kotlin Programming"
        course.studentList shouldHaveSize 3

        course.studentList[0].id shouldBeEqualTo 1
        course.studentList[0].email shouldBeEqualTo "john.doe@example.com"
        course.studentList[0].phoneList shouldHaveSize 1
        course.studentList[0].phoneList[0].number shouldBeEqualTo "010-1234-5678"

        course.studentList[1].id shouldBeEqualTo 2
        course.studentList[1].email shouldBeEqualTo "richard.roe@example.com"
        course.studentList[1].phoneList.shouldBeEmpty()

        course.studentList[2].id shouldBeEqualTo 3
        course.studentList[2].email shouldBeEqualTo "jane.doe@example.com"
        course.studentList[2].phoneList.shouldBeEmpty()
    }

    @Test
    fun `convert protobuf to json`() = runSuspendIO {
        val course1 = client.get()
            .uri("/courses/1")
            .accept(MediaType.APPLICATION_PROTOBUF)
            .retrieve()
            .awaitBody<Course>()

        // JSON Format Text
        val jsonText = course1.toJson()
        log.debug { "jsonText=$jsonText" }
        jsonText shouldContain "Kotlin Programming"
        jsonText shouldContain "john.doe@example.com"
    }
}
