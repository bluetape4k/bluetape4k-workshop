package io.bluetape4k.workshop.micrometer.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.logging.info
import io.bluetape4k.support.requirePositiveNumber
import io.bluetape4k.workshop.micrometer.model.Todo
import io.bluetape4k.workshop.micrometer.service.SyncService
import io.micrometer.observation.annotation.Observed
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 동기방식의 Controller 에 대해서는 기본적으로 Observation 이 적용됩니다.
 */
@RestController
@RequestMapping("/sync")
class SyncController(private val syncService: SyncService) {

    companion object : KLogging() {
        private const val SIMULATED_BLOCKING_WORK_MILLIS = 100L
    }

    @Observed(contextualName = "sync-get-name-at-controller")
    @GetMapping("/name")
    fun getName(): String {
        log.info { "Get name in sync" }
        return syncService.getName()
    }

    @Observed(contextualName = "sync-get-todo-at-controller")
    @GetMapping("/todos/{id}")
    fun getTodo(@PathVariable(required = true) id: Int): Todo? {
        val todoId = id.requirePositiveNumber("id")
        log.debug { "Get todo[$todoId] in sync" }
        simulateBlockingWork()
        return syncService.getTodo(todoId).apply {
            simulateBlockingWork()
        }
    }

    private fun simulateBlockingWork() {
        Thread.sleep(SIMULATED_BLOCKING_WORK_MILLIS)
    }
}
