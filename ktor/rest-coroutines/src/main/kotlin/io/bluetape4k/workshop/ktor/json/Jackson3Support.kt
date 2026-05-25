package io.bluetape4k.workshop.ktor.json

import io.bluetape4k.logging.KLogging
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Jackson 3 support for NDJSON (newline-delimited JSON) export.
 *
 * ## Behavior / Contract
 * - Uses `tools.jackson.*` (Jackson 3) exclusively — no `com.fasterxml.jackson.*` imports.
 * - [writeNdjson] writes one JSON object per line to the provided [ByteWriteChannel].
 * - [ByteWriteChannel.writeStringUtf8] is a non-blocking suspend extension — **no `withContext(Dispatchers.IO)` needed**.
 */
class Jackson3Support {

    companion object : KLogging() {
        val objectMapper: ObjectMapper = jacksonObjectMapper()
    }

    /**
     * Writes each element of [items] as a JSON line followed by `\n` to [channel].
     *
     * @param channel the [ByteWriteChannel] to write to (typically from `respondBytesWriter`).
     * @param items the objects to serialize, one per line.
     */
    suspend fun <T : Any> writeNdjson(channel: ByteWriteChannel, items: Iterable<T>) {
        for (item in items) {
            val line = objectMapper.writeValueAsString(item) + "\n"
            channel.writeStringUtf8(line)
        }
    }
}
