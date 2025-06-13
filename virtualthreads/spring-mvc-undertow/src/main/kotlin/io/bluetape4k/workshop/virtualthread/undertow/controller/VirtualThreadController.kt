package io.bluetape4k.workshop.virtualthread.undertow.controller

import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ExecutorService
import kotlin.random.Random

@RestController
@RequestMapping("/virtual-thread")
class VirtualThreadController(private val taskExecutor: ExecutorService) {

    companion object: KLoggingChannel() {
        private const val SLEEP_TIME = 300L
    }

    @GetMapping
    fun index(): String {
        val virtualFuture = virtualFuture(taskExecutor) {
            log.debug { "Virtual thread 환경에서 실행됩니다." }
            Thread.sleep(SLEEP_TIME)
            "안녕하세요, Virtual Thread! (${Thread.currentThread()})"
        }
        return virtualFuture.await()
    }

    @GetMapping("/multi")
    fun multipleTasks(): String {
        val taskSize = 100

        val tasks = List(taskSize) {
            virtualFuture(executor = taskExecutor) {
                Thread.sleep(Random.nextLong(500, 1000))
                log.debug { "Task $it is done. (${Thread.currentThread()})" }
            }
        }
        tasks.forEach { it.await() }

        return "Run multiple[$taskSize] tasks. (${Thread.currentThread()})"
    }
}
