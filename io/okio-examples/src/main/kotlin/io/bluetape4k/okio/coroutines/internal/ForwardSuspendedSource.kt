package io.bluetape4k.okio.coroutines.internal

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.okio.coroutines.SuspendedSource
import okio.Buffer
import okio.Source

internal class ForwardSuspendedSource(val delegate: Source): SuspendedSource {

    companion object: KLoggingChannel()

    override suspend fun read(sink: Buffer, byteCount: Long): Long {
        return delegate.read(sink, byteCount)
    }

    override suspend fun close() {
        delegate.close()
    }

    override fun timeout(): okio.Timeout = delegate.timeout()
}
