import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HighContentionProcessProbeTaskTest {

    @Test
    fun `reaper rediscovers descendants and reaches stable zero`() {
        val clock = MutableClock()
        val lateChild = FakeProcess(pid = 103, ignoresGracefulTermination = true)
        val child = FakeProcess(pid = 102, ignoresGracefulTermination = true)
        val worker = FakeProcess(pid = 101, descendants = mutableListOf(child))
        val wrapper = FakeProcess(pid = 100, descendants = mutableListOf(worker))
        val processes = mutableMapOf(
            wrapper.pid to wrapper,
            worker.pid to worker,
            child.pid to child,
            lateChild.pid to lateChild,
        )
        val reaper = HighContentionProcessReaper(
            discoverOwned = {
                if (clock.nanoTime() >= 20_000_000) listOf(lateChild) else emptyList()
            },
            nanoTime = clock::nanoTime,
            sleepMillis = { millis ->
                clock.advanceMillis(millis)
            },
        )

        val reaped = reaper.reap(
            rootProcesses = listOf(wrapper),
            gracefulTimeoutMillis = 10,
            absoluteDeadlineNanos = 1_000_000_000,
            quietPeriodMillis = 30,
            pollIntervalMillis = 10,
        )

        assertEquals(setOf(100L, 101L, 102L, 103L), reaped)
        processes.values.forEach { assertFalse(it.isAlive) }
        assertEquals(1, child.forceCount)
        assertEquals(1, lateChild.forceCount)
    }

    @Test
    fun `reaper fails closed when an owned process survives force`() {
        val clock = MutableClock()
        val survivor = FakeProcess(
            pid = 202,
            ignoresGracefulTermination = true,
            ignoresForcedTermination = true,
        )
        val wrapper = FakeProcess(pid = 200, descendants = mutableListOf(survivor))
        val reaper = HighContentionProcessReaper(
            nanoTime = clock::nanoTime,
            sleepMillis = clock::advanceMillis,
        )

        assertFailsWith<IllegalStateException> {
            reaper.reap(
                rootProcesses = listOf(wrapper),
                gracefulTimeoutMillis = 10,
                absoluteDeadlineNanos = 80_000_000,
                quietPeriodMillis = 20,
                pollIntervalMillis = 10,
            )
        }
    }

    @Test
    fun `reaper distinguishes an owned pid reuse from an unrelated process identity`() {
        val clock = MutableClock()
        val original = FakeProcess(pid = 300)
        val ownedReuse = FakeProcess(pid = 300, ignoresGracefulTermination = true)
        val unrelatedReuse = FakeProcess(pid = 300)
        val reaper = HighContentionProcessReaper(
            discoverOwned = {
                if (clock.nanoTime() >= 20_000_000) listOf(ownedReuse) else emptyList()
            },
            nanoTime = clock::nanoTime,
            sleepMillis = clock::advanceMillis,
        )

        reaper.reap(
            rootProcesses = listOf(original),
            gracefulTimeoutMillis = 10,
            absoluteDeadlineNanos = 100_000_000,
            quietPeriodMillis = 20,
            pollIntervalMillis = 10,
        )

        assertTrue(unrelatedReuse.isAlive)
        assertEquals(0, unrelatedReuse.forceCount)
        assertFalse(ownedReuse.isAlive)
        assertEquals(1, ownedReuse.forceCount)
    }

    private class MutableClock {
        private var nowNanos = 0L

        fun nanoTime(): Long = nowNanos

        fun advanceMillis(millis: Long) {
            nowNanos += millis * 1_000_000
        }
    }

    private class FakeProcess(
        override val pid: Long,
        override val descendants: MutableList<FakeProcess> = mutableListOf(),
        private val ignoresGracefulTermination: Boolean = false,
        private val ignoresForcedTermination: Boolean = false,
    ) : HighContentionProcessRef {

        override val identity: Any = Any()
        override var isAlive: Boolean = true
        var forceCount: Int = 0
            private set

        override fun destroy() {
            if (!ignoresGracefulTermination) {
                isAlive = false
            }
        }

        override fun destroyForcibly() {
            forceCount += 1
            if (!ignoresForcedTermination) {
                isAlive = false
            }
        }
    }
}
