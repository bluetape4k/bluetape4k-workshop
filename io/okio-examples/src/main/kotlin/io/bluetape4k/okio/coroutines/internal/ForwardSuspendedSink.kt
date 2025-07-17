package io.bluetape4k.okio.coroutines.internal

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.okio.coroutines.SuspendedSink
import okio.Buffer
import okio.Sink

internal class ForwardSuspendedSink(val delegate: Sink): SuspendedSink {

    companion object: KLoggingChannel()

    override suspend fun write(source: Buffer, byteCount: Long) {
        delegate.write(source, byteCount)
    }

    override suspend fun flush() {
        delegate.flush()
    }

    override suspend fun close() {
        delegate.close()
    }

    override fun timeout() = delegate.timeout()
}
