package io.bluetape4k.workshop.micrometer.service

import io.bluetape4k.concurrent.virtualthread.VT
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.micrometer.observation.withObservation
import io.bluetape4k.workshop.micrometer.model.Todo
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.annotation.Observed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import net.datafaker.Faker
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

/**
 * Custom Observation을 할 때에는 `withObservation` 함수를 사용하여 관찰을 합니다.
 */
@Service
class SyncService(
    private val webClientBuilder: WebClient.Builder,
    private val observationRegistry: ObservationRegistry,
) {
    companion object: KLogging() {
        val faker = Faker()
    }

    private val client = webClientBuilder.baseUrl("https://jsonplaceholder.typicode.com").build()

    @Observed(contextualName = "sync-get-name-at-service")
    fun getName(): String {
        log.debug { "Get fake name in sync service." }
        Thread.sleep(100)

        return Observation
            .createNotStarted("sync-get-name-at-function", observationRegistry)
            .observe<String> {
                log.debug { "Get fake name in sync service." }
                Thread.sleep(100)
                faker.name().fullName()
            }

        // Nested span 으로 사용하기 위해서는 @Observed 대신 `withObservation` 함수를 사용하여 관찰을 합니다.
//        return withObservation("sync-get-name-at-function", observationRegistry) {
//            Thread.sleep(100)
//            log.debug { "Get fake name in nested span" }
//            faker.name().fullName()
//        }
//            .apply {
//                Thread.sleep(100)
//            }
    }

    @Observed(contextualName = "sync-get-todo-at-service")
    fun getTodo(todoId: Int): Todo? {
        preProcessing()
        val todo = getTodoById(todoId)
        postProcessing()
        return todo
    }

    private fun getTodoById(id: Int): Todo? {
        return Observation
            .createNotStarted("get-todo-by-webclient", observationRegistry)
            .observe<Todo?> {
                log.debug { "Get todo by id[$id] in sync service." }
                Thread.sleep(100)

                runBlocking(Dispatchers.VT) {
                    client.get()
                        .uri("/todos/${id}")
                        .retrieve()
                        .bodyToMono<Todo>()
                        .awaitSingleOrNull()
                }
            }

//        return withObservation("get-todo-by-webclient", observationRegistry) {
//            log.debug { "Get todo by id[$id] in sync service." }
//            Thread.sleep(100)
//
//            runBlocking(Dispatchers.VT) {
//                client.get()
//                    .uri("/todos/${id}")
//                    .retrieve()
//                    .bodyToMono<Todo>()
//                    .awaitSingleOrNull()
//            } as? Todo
//        }
    }

    private fun preProcessing() {
        withObservation("pre-processing", observationRegistry) {
            log.debug { "Pre processing ..." }
            Thread.sleep(100)
        }
    }

    private fun postProcessing() {
        withObservation("post-processing", observationRegistry) {
            log.debug { "Post processing ..." }
            Thread.sleep(100)

        }
    }
}
