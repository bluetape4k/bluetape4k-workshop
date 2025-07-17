package io.bluetape4k.okio.coroutines

import io.bluetape4k.okio.coroutines.internal.ForwardBlockingSource
import io.bluetape4k.okio.coroutines.internal.ForwardSuspendedSource
import kotlinx.coroutines.Dispatchers
import okio.Source
import kotlin.coroutines.CoroutineContext

fun Source.toSuspend(context: CoroutineContext = Dispatchers.IO): SuspendedSource = when (this) {
    is ForwardBlockingSource -> this.delegate
    else -> ForwardSuspendedSource(this)
}

fun SuspendedSource.toBlocking(context: CoroutineContext = Dispatchers.IO): Source = when (this) {
    is ForwardSuspendedSource -> this.delegate
    else -> ForwardBlockingSource(this, context)
}
