package io.bluetape4k.workshop.commerce.metering.worker

import io.bluetape4k.workshop.commerce.metering.idempotency.CommandReceiptService
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class CommandReceiptCleanupWorker(
    private val commandReceiptService: CommandReceiptService,
) {
    @Scheduled(fixedDelayString = "PT5M")
    fun cleanup(): Unit {
        commandReceiptService.cleanupExpiredTerminal()
    }
}
