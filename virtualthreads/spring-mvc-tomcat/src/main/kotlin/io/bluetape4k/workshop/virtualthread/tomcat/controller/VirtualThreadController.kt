package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.concurrent.virtualthread.structuredTaskScopeAll
import io.bluetape4k.concurrent.virtualthread.virtualFutureAll
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.springframework.scheduling.annotation.Async
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ExecutorService
import kotlin.random.Random

@RestController
@RequestMapping("/virtual-thread")
class VirtualThreadController(private val executor: ExecutorService) {

    companion object: KLoggingChannel() {
        private const val SLEEP_TIME = 300L
    }

    private val factory = Thread.ofVirtual().name("vt-multi-", 0).factory()

    @GetMapping("", "/")
    fun index(): String {
        log.debug { "Virtual thread 환경에서 실행됩니다." }
        asyncTask()
        return "안녕하세요, Virtual Thread! (${Thread.currentThread()})"
    }

    @GetMapping("/multi")
    fun multipleTasks(): String {
        val taskSize = 100

        // 모든 Task 를 수행하고, Subtask 에 예외가 있다면, 예외를 던진다.
        structuredTaskScopeAll("multi", factory) { scope ->
            repeat(taskSize) {
                scope.fork {
                    Thread.sleep(Random.nextLong(500, 1000))
                    log.debug { "Task $it is done. (${Thread.currentThread()})" }
                }
            }
            scope.join().throwIfFailed()
            Unit
        }

        return "Run multiple[$taskSize] tasks. (${Thread.currentThread()})"
    }

    @GetMapping("/virtualFutureAll")
    fun multipleTasksWithVirtualFuture(): String {
        val taskSize = 100

        val tasks = List(taskSize) {
            {
                Thread.sleep(1000)
                log.debug { "Task $it is done. (${Thread.currentThread()})" }
            }
        }
        virtualFutureAll(tasks, executor).await()

        return "Run multiple[$taskSize] tasks. (${Thread.currentThread()})"
    }

    @Async
    fun asyncTask() {
        Thread.sleep(SLEEP_TIME)
        log.debug { "Async Task is running. (${Thread.currentThread()})" }
    }
}
