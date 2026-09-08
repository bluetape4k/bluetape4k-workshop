package io.bluetape4k.workshop.ktor.json

import io.bluetape4k.jackson3.Jackson
import io.bluetape4k.logging.KLogging
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import tools.jackson.databind.ObjectMapper

/**
 * NDJSON(newline-delimited JSON) export 를 위한 Jackson 3 support 입니다.
 *
 * ## Behavior / Contract
 * - `tools.jackson.*` (Jackson 3) 만 사용하며 `com.fasterxml.jackson.*` import 는 사용하지 않습니다.
 * - Bluetape Jackson3의 공유 기본 mapper를 읽기 전용으로 재사용하며 전역 설정을 변경하지 않습니다.
 * - [writeNdjson] 는 전달된 [ByteWriteChannel] 에 line 마다 JSON object 하나를 씁니다.
 * - [ByteWriteChannel.writeStringUtf8] 는 non-blocking suspend extension 이므로 **`withContext(Dispatchers.IO)` 가 필요 없습니다**.
 */
class Jackson3Support {

    companion object : KLogging() {
        val objectMapper: ObjectMapper = Jackson.defaultJsonMapper
    }

    /**
     * [items] 의 각 element 를 JSON line 으로 쓰고 뒤에 `\n` 를 붙여 [channel] 에 기록합니다.
     *
     * @param channel 쓸 대상 [ByteWriteChannel] 입니다. 일반적으로 `respondBytesWriter` 에서 받습니다.
     * @param items line 마다 하나씩 serialize 할 object 입니다.
     */
    suspend fun <T : Any> writeNdjson(channel: ByteWriteChannel, items: Iterable<T>) {
        for (item in items) {
            val line = objectMapper.writeValueAsString(item) + "\n"
            channel.writeStringUtf8(line)
        }
    }
}
