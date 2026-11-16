package io.bluetape4k.workshop.commerce.metering.worker

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CommandReceiptCleanupWorker(
    private val commandReceiptService: CommandReceiptService,
) {

    companion object: KLogging()

    @Scheduled(fixedDelayString = "PT5M")
    fun cleanup() {
        commandReceiptService.cleanupExpiredTerminal()
    }
}
