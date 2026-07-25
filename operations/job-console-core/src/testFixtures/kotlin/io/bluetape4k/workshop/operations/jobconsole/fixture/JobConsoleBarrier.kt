package io.bluetape4k.workshop.operations.jobconsole.fixture

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JobConsoleBarrier(parties: Int) {
    private val ready = CountDownLatch(parties)
    private val start = CountDownLatch(1)

    fun readyAndAwait(timeout: Duration): Boolean {
        ready.countDown()
        return start.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun releaseWhenReady(timeout: Duration): Boolean =
        ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS).also { if (it) start.countDown() }

    fun awaitReady(timeout: Duration): Boolean =
        ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS)

    fun release() {
        start.countDown()
    }
}
