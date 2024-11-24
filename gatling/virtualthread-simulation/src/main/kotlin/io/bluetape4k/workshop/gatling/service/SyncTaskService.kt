package io.bluetape4k.workshop.gatling.service

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import org.springframework.stereotype.Service

@Service
class SyncTaskService {

    companion object: KLogging()

    fun delay(seconds: Int) {
        log.debug { "Sync Task started..." }
        Thread.sleep(seconds * 1000L)
        log.debug { "Sync Task completed!" }
    }
}
