package io.bluetape4k.workshop.cbor

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.cbor.course.Course
import io.bluetape4k.workshop.cbor.course.PhoneType
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.http.codec.cbor.JacksonCborDecoder
import org.springframework.http.codec.cbor.JacksonCborEncoder
import org.springframework.http.converter.cbor.JacksonCborHttpMessageConverter
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.body
import org.springframework.web.client.getForEntity
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@SpringBootTest(
    classes = [CborApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class CborApplicationTest {

    companion object: KLoggingChannel()

    private val restTemplate: RestTemplate by lazy {
        RestTemplate(listOf(JacksonCborHttpMessageConverter()))
    }

    @LocalServerPort
    private var port: Int = 8080

    private val baseUrl by lazy { "http://localhost:$port" }

    private val restClient by lazy {
        RestClient.builder()
            .configureMessageConverters {
                it.addCustomConverter(JacksonCborHttpMessageConverter())
            }
            .baseUrl(baseUrl)
            .build()
    }

    private val client: WebClient by lazy {
        val strategies = ExchangeStrategies.builder()
            .codecs { cfg ->
                cfg.defaultCodecs().jacksonJsonDecoder(JacksonCborDecoder())
                cfg.defaultCodecs().jacksonJsonEncoder(JacksonCborEncoder())
            }
            .build()

        WebClient.builder()
            .exchangeStrategies(strategies)
            .baseUrl(baseUrl)
            .build()
    }

    private val testClient: WebTestClient by lazy {
        val strategies = ExchangeStrategies.builder()
            .codecs { cfg ->
                cfg.defaultCodecs().jacksonJsonDecoder(JacksonCborDecoder())
                cfg.defaultCodecs().jacksonJsonEncoder(JacksonCborEncoder())
            }
            .build()

        WebTestClient.bindToServer()
            .exchangeStrategies(strategies)
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
        log.debug { "course1: $course1" }
        assertCourse1(course1)
    }

    @Test
    fun `using restClient`() {
        val course1 = restClient.get()
            .uri("/courses/1")
            .retrieve()
            .body<Course>()

        course1.shouldNotBeNull()
        log.debug { "course1: $course1" }
        assertCourse1(course1)
    }

    @Disabled("WebClient 에서는 아직 CBOR 지원을 하지 않습니다.")
    @Test
    fun `using webClient`() = runSuspendIO {
        val course1 = client.get()
            .uri("/courses/1")
            .accept(MediaType.APPLICATION_CBOR)
            .retrieve()
            .bodyToMono<Course>()
            .awaitSingle()

        course1.shouldNotBeNull()
        log.debug { "course1: $course1" }
        assertCourse1(course1)
    }

    @Disabled("WebTestClient 에서는 아직 CBOR 지원을 하지 않습니다.")
    @Test
    fun `using webTestClient`() = runSuspendIO {
        val course1 = testClient.get()
            .uri("/courses/1")
            .accept(MediaType.APPLICATION_CBOR)
            .exchange()
            .expectStatus().isOk
            .returnResult<Course>().responseBody
            .awaitSingle()

        course1.shouldNotBeNull()
        log.debug { "course1: $course1" }
        assertCourse1(course1)
    }

    private fun assertCourse1(course: Course) {
        course.id shouldBeEqualTo 1
        course.name shouldBeEqualTo "Kotlin Programming"
        course.students shouldHaveSize 3

        course.students[0].id shouldBeEqualTo 1
        course.students[0].email shouldBeEqualTo "john.doe@example.com"
        course.students[0].phones shouldHaveSize 1
        course.students[0].phones[0].number shouldBeEqualTo "010-1234-5678"
        course.students[0].phones[0].type shouldBeEqualTo PhoneType.MOBILE

        course.students[1].id shouldBeEqualTo 2
        course.students[1].email shouldBeEqualTo "richard.roe@example.com"
        course.students[1].phones[0].number shouldBeEqualTo "234567"
        course.students[1].phones[0].type shouldBeEqualTo PhoneType.LANDLINE

        course.students[2].id shouldBeEqualTo 3
        course.students[2].email shouldBeEqualTo "jane.doe@example.com"
        course.students[2].phones shouldHaveSize 2
    }
}
