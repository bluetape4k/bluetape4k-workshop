package io.bluetape4k.okio.coroutines.internal

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.okio.coroutines.SuspendedSource
import io.bluetape4k.okio.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Source
import okio.Timeout
import kotlin.coroutines.CoroutineContext

internal class ForwardBlockingSource(
    val delegate: SuspendedSource,
    val context: CoroutineContext = Dispatchers.IO,
): Source {

    companion object: KLoggingChannel()

    override fun read(sink: Buffer, byteCount: Long): Long = runBlocking(context) {
        withTimeoutOrNull(timeout()) {
            delegate.read(sink, byteCount)
        } ?: -1L

    }

    override fun close() = runBlocking(context) {
        withTimeoutOrNull(timeout()) {
            delegate.close()
        } ?: Unit
    }

    override fun timeout(): Timeout = delegate.timeout()

}
