package io.bluetape4k.workshop.protobuf.convert

import com.google.protobuf.AbstractMessage.Builder
import com.google.protobuf.Message
import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat

/**
 * Protobug 객체를 JSON 문자열로 변환합니다.
 */
fun MessageOrBuilder.toJson(): String = JsonFormat.printer().print(this)

/**
 * JSON 문자열을 Protobuf 객체로 변환합니다.
 */
fun messageFromJson(json: String): Message {
    val builder = Struct.newBuilder()
    JsonFormat.parser().ignoringUnknownFields().merge(json, builder)
    return builder.build()
}

inline fun <reified T: Message> messageFromJsonOrNull(json: String): T? {
    // val builder = T::class.members.find { it.name == "newBuilder" }?.callBy(emptyMap()) as Builder<*>
    val builder: Builder<*>? = T::class.java.getMethod("newBuilder").invoke(null) as? Builder<*>
    return builder?.let {
        JsonFormat.parser().ignoringUnknownFields().merge(json, it)
        it.build() as? T
    }
}
