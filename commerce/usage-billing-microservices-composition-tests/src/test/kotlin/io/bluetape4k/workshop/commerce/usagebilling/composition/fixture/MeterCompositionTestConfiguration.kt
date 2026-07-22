package io.bluetape4k.workshop.commerce.usagebilling.composition.fixture

import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.KafkaMeterEventTransport
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterEventTransport
import io.bluetape4k.workshop.commerce.usagebilling.meter.messaging.MeterEventTransportFailure
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Deterministic transport fault injection for composition tests; normal delivery still uses Kafka. */
@TestConfiguration(proxyBeanMethods = false)
class MeterCompositionTestConfiguration {
    @Bean
    fun meterTransportFailureSwitch(): MeterTransportFailureSwitch = MeterTransportFailureSwitch()

    @Bean
    @Primary
    fun meterCompositionTransport(
        failureSwitch: MeterTransportFailureSwitch,
        delegate: KafkaMeterEventTransport,
    ): MeterEventTransport =
        object : MeterEventTransport {
            override fun publish(partitionKey: String, payload: String) {
                if (failureSwitch.isFailing) {
                    throw MeterEventTransportFailure(IllegalStateException("composition_meter_transport_unavailable"))
                }
                delegate.publish(partitionKey, payload)
            }
        }
}

class MeterTransportFailureSwitch {
    @Volatile
    var isFailing: Boolean = false
}
