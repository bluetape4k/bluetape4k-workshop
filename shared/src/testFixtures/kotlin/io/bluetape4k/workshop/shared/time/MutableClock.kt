package io.bluetape4k.workshop.shared.time

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * 여러 테스트가 같은 wall-clock timeline을 결정적으로 제어하기 위한 thread-safe clock이다.
 *
 * `withZone`은 같은 timeline을 공유하는 view를 반환하며, monotonic time이나 scheduler
 * semantics는 제공하지 않는다.
 */
open class MutableClock private constructor(
    private val current: AtomicReference<Instant>,
    private val zone: ZoneId,
) : Clock() {

    /** 주어진 시각과 time zone으로 clock을 시작한다. */
    constructor(initial: Instant, zone: ZoneId = ZoneOffset.UTC) : this(AtomicReference(initial), zone)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): MutableClock =
        if (zone == this.zone) this else MutableClock(current, zone)

    override fun instant(): Instant = current.get()

    /** 현재 시각을 duration만큼 이동하고 이동한 시각을 반환한다. */
    fun advance(duration: Duration): Instant = current.updateAndGet { it.plus(duration) }

    /** 현재 시각을 초 단위로 이동하고 이동한 시각을 반환한다. */
    fun advanceSeconds(seconds: Long): Instant = advance(Duration.ofSeconds(seconds))
}
