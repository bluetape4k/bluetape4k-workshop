package io.bluetape4k.workshop.problem.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpDelete
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPut
import io.bluetape4k.support.toUtf8String
import io.bluetape4k.workshop.problem.AbstractProblemTest
import io.bluetape4k.workshop.problem.controller.TaskController.Task
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class TaskControllerTest(
    @param:Autowired private val client: WebTestClient,
): AbstractProblemTest() {

    companion object: KLogging()

    @Test
    fun `context loading`() {
        client.shouldNotBeNull()
    }

    @Test
    fun `get all tasks`() = runSuspendIO {
        val tasks = client
            .httpGet("/tasks")
            .returnResult<Task>().responseBody
            .asFlow()
            .toList()

        tasks.shouldNotBeEmpty()
        tasks.forEach {
            log.debug { it }
        }
    }

    @Test
    fun `get task by valid id`() = runSuspendIO {
        val task = client
            .httpGet("/tasks/1")
            .returnResult<Task>().responseBody
            .awaitSingle()

        task.id shouldBeEqualTo 1L
    }

    /**
     * Get invalid task id
     *
     * Response:
     * ```json
     * {
     *     "title": "Bad Request",
     *     "status": 400,
     *     "detail": "400 BAD_REQUEST \"Type mismatch.\"; nested exception is org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'long'; nested exception is java.lang.NumberFormatException: For input string: \"abc\"",
     *     "cause": {
     *         "title": "Internal Server Error",
     *         "status": 500,
     *         "detail": "Failed to convert value of type 'java.lang.String' to required type 'long'; nested exception is java.lang.NumberFormatException: For input string: \"abc\"",
     *         "cause": {
     *             "title": "Internal Server Error",
     *             "status": 500,
     *             "detail": "For input string: \"abc\""
     *         }
     *     }
     * }
     * ```
     */
    @Test
    fun `get task with invalid format id`() = runSuspendIO {
        client.httpGet("/tasks/abc", HttpStatus.BAD_REQUEST)
            .expectBody()
            .jsonPath("$.title").isEqualTo("Bad Request")
            .consumeWith { result ->
                val body = result.responseBody!!
                log.debug { body.toUtf8String() }
            }
    }

    /**
     * Get task non-existing id
     *
     * ```json
     * {
     *     "title": "찾는 Task 없음",
     *     "status": 404,
     *     "detail": "TaskId[9999]에 해당하는 Task를 찾을 수 없습니다.",
     *     "instance": "/tasks/9999"
     * }
     * ```
     */
    @Test
    fun `get task non-existing id`() = runSuspendIO {
        client.httpGet("/tasks/9999", HttpStatus.NOT_FOUND)
            .expectBody()
            .jsonPath("$.title").isEqualTo("찾는 Task 없음")
            .consumeWith { result ->
                val body = result.responseBody!!
                log.debug { body.toUtf8String() }
            }
    }

    /**
     * [UnsupportedOperationException] 이 발생하는 경우
     *
     * ```json
     * {
     *     "title": "Not Implemented",
     *     "status": 501,
     *     "detail": "구현 중",
     *     "cause": {
     *         "title": "Internal Server Error",
     *         "status": 500,
     *         "detail": "Boom!"
     *     }
     * }
     * ```
     */
    @Test
    fun `when call api which throw UnsupportedOperationException`() = runSuspendIO {
        client.httpPut("/tasks/1", httpStatus = HttpStatus.NOT_IMPLEMENTED)
            .expectBody()
            .jsonPath("$.detail").isEqualTo("구현 중")
            .consumeWith { result ->
                val body = result.responseBody!!
                log.debug { body.toUtf8String() }
            }
    }

    /**
     * When call api which throw [AccessDeniedException]
     *
     * ```json
     * {
     *     "title": "Internal Server Error",
     *     "status": 500,
     *     "detail": "You can't delete this task [1]"
     * }
     * ```
     */
    @Test
    fun `when call api which throw AccessDeniedException`() = runSuspendIO {
        client.httpDelete("/tasks/1", HttpStatus.INTERNAL_SERVER_ERROR)
            .expectBody()
            .jsonPath("$.detail").isEqualTo("You can't delete this task [1]")
            .consumeWith { result ->
                val body = result.responseBody!!
                log.debug { body.toUtf8String() }
            }
        client.httpDelete("/tasks/1", HttpStatus.INTERNAL_SERVER_ERROR)
            .expectBody()
            .jsonPath("$.detail").isEqualTo("You can't delete this task [1]")
            .consumeWith { result ->
                val body = result.responseBody!!
                log.debug { body.toUtf8String() }
            }
    }
}
