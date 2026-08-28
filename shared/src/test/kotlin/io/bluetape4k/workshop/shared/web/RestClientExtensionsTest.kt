package io.bluetape4k.workshop.shared.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpDelete
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpHead
import io.bluetape4k.spring.tests.httpOptions
import io.bluetape4k.spring.tests.httpPatch
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.spring.tests.httpPut
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

class RestClientExtensionsTest : AbstractSpringTest() {

    companion object : KLoggingChannel()

    private val client: RestClient = RestClient
        .builder()
        .baseUrl(baseUrl)
        .build()

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation::class)
    inner class Get {
        @Test
        @Order(1)
        fun `httpGet httpbin`() {
            val response = client.httpGet("/get")
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/get"
        }

        @Test
        @Order(2)
        fun `httpGet httpbin anything`() {
            val response = client.httpGet("/anything")
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/anything"
        }

        @Test
        @Order(3)
        fun `httpGet httpbin not found`() {
            assertFailsWith<HttpClientErrorException.NotFound> {
                client.httpGet("/not-existing").toEntity<String>()
            }
        }
    }

    @Nested
    inner class Post {
        @Test
        fun `httpPost httpbin`() {
            val response = client.httpPost("/post")
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/post"
        }

        @Test
        fun `httpPost httpbin with body`() {
            val response = client.httpPost("/post", "Hello, World!")
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/post"
            response shouldContain "Hello, World!"
        }

        @Test
        fun `httpPost httpbin with flow`() {
            val response = client.httpPost("/post", flowOf("Hello", ",", "World!"))
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/post"
        }
    }

    @Nested
    inner class Patch {
        @Test
        fun `httpPatch httpbin`() {
            val response = client.httpPatch("/patch")
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/patch"
        }

        @Test
        fun `httpPatch httpbin with body`() {
            val response = client.httpPatch("/patch", "Hello, World!")
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/patch"
            response shouldContain "Hello, World!"
        }

    }

    @Nested
    inner class Put {
        @Test
        fun `httpPut httpbin`() {
            val response = client.httpPut("/put")
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/put"
        }

        @Test
        fun `httpPut httpbin with body`() {
            val response = client.httpPut("/put", "Hello, World!")
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/put"
            response shouldContain "Hello, World!"
        }

        @Test
        fun `httpPut httpbin with flow`() {
            val response = client.httpPut("/put", flowOf("Hello", ",", "World!"))
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/put"
        }
    }

    @Nested
    inner class Delete {
        @Test
        fun `httpDelete httpbin`() {
            val response = client.httpDelete("/delete")
                .toEntity<String>()
                .body.shouldNotBeNull()

            log.debug { "response=$response" }
            response shouldContain "$baseUrl/delete"
        }
    }

    @Nested
    inner class Head {
        @Test
        fun `httpHead httpbin`() {
            client.httpHead("/get").toBodilessEntity().statusCode.is2xxSuccessful.shouldBeTrue()
        }
    }

    @Nested
    inner class Options {
        @Test
        fun `httpOptions httpbin`() {
            client.httpOptions("/anything").toBodilessEntity().body.shouldBeNull()
        }
    }
}
