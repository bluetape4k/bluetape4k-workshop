package io.bluetape4k.workshop.protobuf.convert

import com.google.protobuf.Message
import io.bluetape4k.protobuf.serializers.ProtobufSerializer
import java.nio.ByteBuffer

private val protobufByteBufferSerializer = ProtobufSerializer()

/**
 * 이 Protobuf [Message]를 caller-owned [target]에 직렬화하고 기록한 byte 수를 반환합니다.
 *
 * 성공하면 [target]의 position은 기록한 byte 수만큼 전진합니다. capacity 부족이나 read-only
 * target은 각각 `BufferOverflowException`, `ReadOnlyBufferException`을 던지며 position은 호출
 * 전 값으로 복구됩니다. 반환 byte는 [ProtobufSerializer.serialize]의 allocating 결과와 같습니다.
 *
 * [target]의 flip/clear와 생명주기는 caller가 소유합니다.
 */
fun Message.serializeTo(target: ByteBuffer): Int =
    protobufByteBufferSerializer.serializeTo(this, target)
