package io.bluetape4k.workshop.jmolecules.example.jpa.customer

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info
import org.jmolecules.ddd.annotation.Service
import org.jmolecules.event.annotation.DomainEventHandler
import org.jmolecules.event.types.DomainEvent

@Service
@org.springframework.stereotype.Service
class CustomerService {

    companion object: KLogging()

    var eventReceived = false

    @DomainEventHandler
    fun on(event: SampleEvent) {
        log.info { "Received event: $event" }
        this.eventReceived = true
    }

    class SampleEvent: DomainEvent
}
