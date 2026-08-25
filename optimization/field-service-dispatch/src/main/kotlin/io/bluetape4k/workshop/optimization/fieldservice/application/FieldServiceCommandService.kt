package io.bluetape4k.workshop.optimization.fieldservice.application

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import io.bluetape4k.workshop.optimization.fieldservice.persistence.EventAppendResult
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceCommand
import io.bluetape4k.workshop.optimization.fieldservice.persistence.FieldServiceRepository
import io.bluetape4k.workshop.optimization.fieldservice.persistence.OutboxRecord
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock

/** Event append와 bounded replan outbox enqueue를 하나의 transaction으로 묶습니다. */
class FieldServiceCommandService(
    private val repository: FieldServiceRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun accept(command: FieldServiceCommand): CommandResult = accept(command) {}

    fun accept(command: FieldServiceCommand, mutation: () -> Unit): CommandResult = transaction {
        when (repository.appendEvent(command)) {
            EventAppendResult.APPENDED -> {
                mutation()
                repository.enqueueOutbox(
                    OutboxRecord(
                        payload = "${command.aggregateType}:${command.aggregateId.value}:${command.eventType.name}",
                        nextAttemptAt = clock.instant(),
                    ),
                )
                log.info { "Field Service command accepted: eventType=${command.eventType.name}" }
                CommandResult.APPLIED
            }
            EventAppendResult.DUPLICATE -> CommandResult.DUPLICATE
            EventAppendResult.EVENT_KEY_REUSED -> CommandResult.EVENT_KEY_REUSED
            EventAppendResult.VERSION_CONFLICT -> CommandResult.VERSION_CONFLICT
        }
    }

    companion object : KLogging()
}

enum class CommandResult {
    APPLIED,
    DUPLICATE,
    EVENT_KEY_REUSED,
    VERSION_CONFLICT,
}
