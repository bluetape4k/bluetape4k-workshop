package org.springframework.web.servlet.mvc.method.annotation

import org.springframework.http.MediaType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/** Test-only bridge that exercises the real emitter callbacks without a servlet container. */
internal class TestSseEmitterHandler : ResponseBodyEmitter.Handler {
    val sent = CopyOnWriteArrayList<String>()
    private var timeoutCallback: Runnable? = null
    private var errorCallback: Consumer<Throwable>? = null
    private var completionCallback: Runnable? = null

    override fun send(
        data: Any,
        mediaType: MediaType?,
    ) {
        sent += data.toString()
    }

    override fun send(data: Set<ResponseBodyEmitter.DataWithMediaType>) {
        sent += data.joinToString(separator = "") { it.data.toString() }
    }

    override fun complete() {
        completionCallback?.run()
    }

    override fun completeWithError(failure: Throwable) {
        errorCallback?.accept(failure)
        completionCallback?.run()
    }

    override fun onTimeout(callback: Runnable) {
        timeoutCallback = callback
    }

    override fun onError(callback: Consumer<Throwable>) {
        errorCallback = callback
    }

    override fun onCompletion(callback: Runnable) {
        completionCallback = callback
    }

    fun triggerTimeout() = timeoutCallback?.run()

    fun triggerDisconnect() = completionCallback?.run()
}

internal fun SseEmitter.attachTestHandler(handler: TestSseEmitterHandler) = initialize(handler)
