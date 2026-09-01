package io.bluetape4k.workshop.leader.tenantscheduler.scheduled

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertTimeout
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.config.ScheduledTaskHolder

class TenantScheduledPolicyLifecycleTest {

    @Test
    fun `pending scheduled task is registered once and removed on context close`() {
        val context = AnnotationConfigApplicationContext(PendingSchedulingConfiguration::class.java)
        val holder = context.getBeansOfType(ScheduledTaskHolder::class.java).values.single()
        try {
            holder.scheduledTasks.size shouldBeEqualTo 1
        } finally {
            context.close()
        }
        holder.scheduledTasks.size shouldBeEqualTo 0
    }

    @Test
    fun `immediate scheduled task triggers through Spring scheduler`() {
        val fixture = ImmediateSchedulingFixture()
        val context = AnnotationConfigApplicationContext().apply {
            registerBean(ImmediateSchedulingConfiguration::class.java, fixture)
            refresh()
        }
        try {
            assertTimeout(Duration.ofSeconds(5)) {
                fixture.started.await(5, TimeUnit.SECONDS).shouldBeTrue()
            }
            val holder = context.getBeansOfType(ScheduledTaskHolder::class.java).values.single()
            holder.scheduledTasks.size shouldBeEqualTo 1
            fixture.invocations.get() shouldBeEqualTo 1
            context.close()
            holder.scheduledTasks.size shouldBeEqualTo 0
        } finally {
            if (context.isActive) context.close()
        }
    }

    @Test
    @Timeout(10)
    fun `closing context while callback is in flight is bounded and does not assume interrupt`() {
        val fixture = InFlightSchedulingFixture()
        val context = AnnotationConfigApplicationContext().apply {
            registerBean(InFlightSchedulingConfiguration::class.java, fixture)
            refresh()
        }
        try {
            fixture.entered.await(5, TimeUnit.SECONDS).shouldBeTrue()
            val holder = context.getBeansOfType(ScheduledTaskHolder::class.java).values.single()
            holder.scheduledTasks.size shouldBeEqualTo 1
            assertTimeout(Duration.ofSeconds(5)) {
                context.close()
            }
            fixture.finished.await(5, TimeUnit.SECONDS).shouldBeTrue()
            holder.scheduledTasks.size shouldBeEqualTo 0
        } finally {
            fixture.release.countDown()
            if (context.isActive) context.close()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class PendingSchedulingConfiguration {
        @Bean
        fun fixture(): PendingSchedulingFixture = PendingSchedulingFixture()
    }

    class PendingSchedulingFixture {
        @Scheduled(fixedDelay = 50, initialDelay = 60_000)
        fun reconcile() = Unit
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class ImmediateSchedulingConfiguration(
        private val fixture: ImmediateSchedulingFixture,
    ) {
        @Bean
        fun fixture(): ImmediateSchedulingFixture = fixture
    }

    class ImmediateSchedulingFixture {
        val started = CountDownLatch(1)
        val invocations = AtomicInteger()

        @Scheduled(fixedDelay = 100, initialDelay = 0)
        fun reconcile() {
            invocations.incrementAndGet()
            started.countDown()
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    class InFlightSchedulingConfiguration(
        private val fixture: InFlightSchedulingFixture,
    ) {
        @Bean
        fun fixture(): InFlightSchedulingFixture = fixture
    }

    class InFlightSchedulingFixture {
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val release = CountDownLatch(1)

        @Scheduled(fixedDelay = 60_000, initialDelay = 0)
        fun reconcile() {
            entered.countDown()
            try {
                release.await(2, TimeUnit.SECONDS)
            } finally {
                finished.countDown()
            }
        }
    }
}
