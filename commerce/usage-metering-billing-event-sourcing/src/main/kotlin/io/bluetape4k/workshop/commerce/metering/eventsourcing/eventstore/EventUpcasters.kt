@file:Suppress("MatchingDeclarationName") // Concrete event upcasters are added beside the shared contract.

package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

fun interface EventUpcaster {
    fun upcast(payload: String): String
}
