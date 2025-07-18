package io.bluetape4k.okio.coroutines

import okio.Buffer
import okio.Timeout

interface SuspendedSink {
    /**
     * [source]로부터 `byteCount` 바이트를 제거하고 이를 현재 Sink에 추가합니다.
     *
     * @param source    읽어드릴 버퍼
     * @param byteCount 읽어들일 바이트 수
     */
    suspend fun write(source: Buffer, byteCount: Long)

    /**
     * 모든 버퍼링된 바이트를 최종 목적지로 전송합니다.
     */
    suspend fun flush()

    /**
     * 모든 버퍼링된 바이트를 최종 목적지로 전송하고 이 [SuspendedSink]가 보유한 리소스를 해제합니다.
     */
    suspend fun close()

    /**
     * 이 [SuspendedSink]의 [Timeout]을 반환합니다.
     */
    fun timeout(): Timeout = Timeout.NONE
}

fun SuspendedSink.buffered(): BufferedSuspendedSink = RealBufferedSuspendedSink(this)
