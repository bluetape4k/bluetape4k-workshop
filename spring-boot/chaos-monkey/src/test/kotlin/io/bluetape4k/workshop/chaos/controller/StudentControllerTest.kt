package io.bluetape4k.workshop.chaos.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.chaos.AbstractChaosApplicationTest
import io.bluetape4k.workshop.chaos.model.Student
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.returnResult

class StudentControllerTest: AbstractChaosApplicationTest() {

    companion object: KLogging()

    @Test
    fun `find all students`() = runSuspendIO {
        val students = client
            .get()
            .uri("/students")
            .exchange()
            .expectStatus().isOk
            .returnResult<Student>().responseBody
            .asFlow()
            .toList()

        log.debug { "all students" }
        students.forEach {
            log.debug { it }
        }
        students.shouldNotBeEmpty()
    }

    @Test
    fun `find by id`() {
        val studentId = 10001
        val student = client
            .get()
            .uri("/students/$studentId")
            .exchange()
            .expectStatus().isOk
            .expectBody<Student>()
            .returnResult().responseBody!!

        student.id shouldBeEqualTo studentId
        log.debug { student }
    }
}
