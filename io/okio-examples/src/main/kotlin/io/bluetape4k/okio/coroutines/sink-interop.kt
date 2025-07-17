package io.bluetape4k.okio.coroutines

import io.bluetape4k.okio.coroutines.internal.ForwardBlockingSink
import io.bluetape4k.okio.coroutines.internal.ForwardSuspendedSink
import kotlinx.coroutines.Dispatchers
import okio.Sink
import kotlin.coroutines.CoroutineContext

fun Sink.toSuspended(context: CoroutineContext = Dispatchers.IO): SuspendedSink = when (this) {
    is ForwardBlockingSink -> this.delegate
    else -> ForwardSuspendedSink(this)
}

fun SuspendedSink.toBlocking(context: CoroutineContext = Dispatchers.IO): Sink = when (this) {
    is ForwardSuspendedSink -> this.delegate
    else -> ForwardBlockingSink(this, context)
}
