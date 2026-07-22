package io.bluetape4k.workshop.commerce.metering.eventsourcing.eventstore

class UnknownEventSchemaException(eventType: String, version: Int) :
    IllegalStateException("unknown_event_schema:$eventType:$version")

class EventCodecRegistry {
    private data class Decoder(val latestVersion: Int, val decode: (String) -> Any)
    private data class UpcasterKey(val eventType: String, val fromVersion: Int)

    private val decoders = linkedMapOf<String, Decoder>()
    private val upcasters = linkedMapOf<UpcasterKey, (String) -> String>()

    fun register(eventType: String, latestVersion: Int, decoder: (String) -> Any): EventCodecRegistry = apply {
        require(eventType.isNotBlank()) { "event_type_invalid" }
        require(latestVersion > 0) { "schema_version_invalid" }
        check(decoders.putIfAbsent(eventType, Decoder(latestVersion, decoder)) == null) {
            "duplicate_event_decoder:$eventType"
        }
    }

    fun registerUpcaster(
        eventType: String,
        fromVersion: Int,
        upcaster: (String) -> String,
    ): EventCodecRegistry = apply {
        require(fromVersion > 0) { "schema_version_invalid" }
        check(upcasters.putIfAbsent(UpcasterKey(eventType, fromVersion), upcaster) == null) {
            "duplicate_event_upcaster:$eventType:$fromVersion"
        }
    }

    fun validate(): EventCodecRegistry = apply {
        decoders.forEach { (eventType, decoder) ->
            for (version in 1 until decoder.latestVersion) {
                check(UpcasterKey(eventType, version) in upcasters) {
                    "broken_upcast_chain:$eventType:$version"
                }
            }
        }
    }

    fun decode(eventType: String, schemaVersion: Int, payload: String): Any {
        val decoder = decoderFor(eventType, schemaVersion)
        if (schemaVersion > decoder.latestVersion || schemaVersion <= 0) {
            throw UnknownEventSchemaException(eventType, schemaVersion)
        }
        var currentPayload = payload
        var currentVersion = schemaVersion
        while (currentVersion < decoder.latestVersion) {
            val upcaster = upcasterFor(eventType, currentVersion)
            currentPayload = upcaster(currentPayload)
            currentVersion += 1
        }
        return decoder.decode(currentPayload)
    }

    private fun decoderFor(eventType: String, version: Int): Decoder =
        decoders[eventType] ?: throw UnknownEventSchemaException(eventType, version)

    private fun upcasterFor(eventType: String, version: Int): (String) -> String =
        upcasters[UpcasterKey(eventType, version)] ?: throw UnknownEventSchemaException(eventType, version)
}
