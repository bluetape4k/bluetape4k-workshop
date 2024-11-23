package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.scheduling.annotation.Async
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.Executors
import java.util.concurrent.StructuredTaskScope

@RestController
@RequestMapping("/virtual-thread")
class VirtualThreadController {

    companion object: KLogging() {
        private const val SLEEP_TIME = 300L
    }

    private val factory = Thread.ofVirtual().name("vt-ctrl-", 1).factory()
    private val executor = Executors.newThreadPerTaskExecutor(factory)

    @GetMapping("", "/")
    fun index(): String {
        log.debug { "Virtual thread 환경에서 실행됩니다." }
        asyncTask()
        return "안녕하세요, Virtual Thread! (${Thread.currentThread()})"
    }

    @GetMapping("/multi")
    fun multipleTasks(): String {
        val taskSize = 1000

        // ShutdownOnFailure - 하나라도 실패하면 즉시 종료 (진행 중인 다른 Task 들은 중단된다)
        StructuredTaskScope.ShutdownOnFailure().use { scope ->
            repeat(taskSize) {
                scope.fork {
                    Thread.sleep(1000)
                    log.debug { "Task $it is done. (${Thread.currentThread()})" }
                }
            }
            scope.join().throwIfFailed()
        }


        return "Run multiple[$taskSize] tasks. (${Thread.currentThread()})"
    }

    @Async
    fun asyncTask() {
        Thread.sleep(SLEEP_TIME)
        log.debug { "Async Task is running. (${Thread.currentThread()})" }
    }
}
