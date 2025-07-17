package io.bluetape4k.okio.coroutines.internal

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.okio.coroutines.SuspendedSink
import io.bluetape4k.okio.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.Sink
import okio.Timeout
import kotlin.coroutines.CoroutineContext

internal class ForwardBlockingSink(
    val delegate: SuspendedSink,
    val context: CoroutineContext = Dispatchers.IO,
): Sink {

    companion object: KLoggingChannel()

    val timeout = delegate.timeout()

    override fun write(source: Buffer, byteCount: Long) = runBlocking(context) {
        withTimeoutOrNull(timeout) {
            delegate.write(source, byteCount)
        } ?: Unit
    }

    override fun flush() = runBlocking(context) {
        withTimeoutOrNull(timeout) {
            delegate.flush()
        } ?: Unit
    }

    override fun timeout(): Timeout = timeout

    override fun close() = runBlocking(context) {
        withTimeoutOrNull(timeout) {
            runBlocking(context) {
                delegate.close()
            }
        } ?: Unit
    }
}
