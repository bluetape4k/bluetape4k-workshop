package io.bluetape4k.workshop.gatling.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.gatling.validation.requireValidDelaySeconds
import org.springframework.stereotype.Service

@Service
class SyncTaskService {

    companion object: KLogging()

    fun delay(seconds: Int) {
        val delaySeconds = seconds.requireValidDelaySeconds()
        log.debug { "Sync Task started..." }
        Thread.sleep(delaySeconds * 1000L)
        log.debug { "Sync Task completed!" }
    }
}
