package io.bluetape4k.workshop.operations.jobconsole.fixture

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

class JobConsoleFixtureClock(
    initial: Instant = Instant.parse("2026-07-21T00:00:00Z"),
) : Clock() {
    private val current = AtomicReference(initial)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = current.get()

    fun advance(duration: Duration): Instant =
        current.updateAndGet { it.plus(duration) }
}
