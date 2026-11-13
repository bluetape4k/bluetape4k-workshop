package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.springframework.scheduling.annotation.Scheduled

/** 주기적으로 durable outbox를 claim하여 replay하는 demo scheduler입니다. */
class FieldServiceOutboxScheduler(
    private val worker: FieldServiceOutboxWorker,
) {

    companion object: KLogging()

    @Scheduled(fixedDelayString = "\${field-service.outbox.poll-ms:1000}")
    fun poll() {
        log.info { "Processing outbox batch..." }
        worker.processOutboxBatch()
    }
}
