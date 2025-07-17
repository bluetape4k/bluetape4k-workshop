package io.bluetape4k.okio.coroutines

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.Timeout
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [AsynchronousSocketChannel]을 [SuspendedSink]로 변환합니다.
 */
fun AsynchronousSocketChannel.asSuspendedSink(context: CoroutineContext = Dispatchers.IO): SuspendedSink {
    val channel = this

    return object: SuspendedSink {
        override suspend fun write(source: Buffer, byteCount: Long) {
            withContext(context) {
                source.readUnsafe()
            }
        }

        override suspend fun flush() {
            // Nothing to do
        }

        override suspend fun close() {
            withContext(context) {
                channel.close()
            }
        }

        override fun timeout(): Timeout = Timeout.NONE
    }
}

internal object ChannelCompletionHandler: CompletionHandler<Int, CancellableContinuation<Int>> {
    override fun completed(result: Int, attachment: CancellableContinuation<Int>) {
        attachment.resume(result)
    }

    override fun failed(exc: Throwable, attachment: CancellableContinuation<Int>) {
        attachment.resumeWithException(exc)
    }
}
