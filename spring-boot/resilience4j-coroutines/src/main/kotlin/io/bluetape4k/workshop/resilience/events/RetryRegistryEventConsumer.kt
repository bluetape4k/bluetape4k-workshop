package io.bluetape4k.workshop.resilience.events

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.info
import io.github.resilience4j.core.registry.EntryAddedEvent
import io.github.resilience4j.core.registry.EntryRemovedEvent
import io.github.resilience4j.core.registry.EntryReplacedEvent
import io.github.resilience4j.core.registry.RegistryEventConsumer
import io.github.resilience4j.retry.Retry

open class RetryRegistryEventConsumer: RegistryEventConsumer<Retry> {

    companion object: KLoggingChannel()

    override fun onEntryAddedEvent(entryAddedEvent: EntryAddedEvent<Retry>) {
        entryAddedEvent.addedEntry.eventPublisher.onEvent { event ->
            log.info { "Entry added. $event" }
        }
    }

    override fun onEntryRemovedEvent(entryRemoveEvent: EntryRemovedEvent<Retry>) {
        entryRemoveEvent.removedEntry.eventPublisher.onEvent { event ->
            log.info { "Entry removed. $event" }
        }
    }

    override fun onEntryReplacedEvent(entryReplacedEvent: EntryReplacedEvent<Retry>) {
        entryReplacedEvent.newEntry.eventPublisher.onEvent { event ->
            log.info { "Entry replaced. $event" }
        }
    }
}
