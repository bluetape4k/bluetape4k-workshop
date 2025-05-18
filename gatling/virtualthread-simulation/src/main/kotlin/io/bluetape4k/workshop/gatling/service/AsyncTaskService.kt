package io.bluetape4k.workshop.gatling.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class AsyncTaskService {

    companion object: KLogging()

    @Async
    fun delay(seconds: Int) {
        log.debug { "Async Task started..." }
        Thread.sleep(seconds * 1000L)
        log.debug { "Async Task completed!" }
    }
}
