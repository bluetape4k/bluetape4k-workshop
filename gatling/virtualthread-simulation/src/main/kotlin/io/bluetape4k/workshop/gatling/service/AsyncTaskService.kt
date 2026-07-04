package io.bluetape4k.workshop.gatling.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.gatling.validation.requireValidDelaySeconds
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class AsyncTaskService {

    companion object: KLogging()

    @Async
    fun delay(seconds: Int) {
        val delaySeconds = seconds.requireValidDelaySeconds()
        log.debug { "Async Task started..." }
        Thread.sleep(delaySeconds * 1000L)
        log.debug { "Async Task completed!" }
    }
}
