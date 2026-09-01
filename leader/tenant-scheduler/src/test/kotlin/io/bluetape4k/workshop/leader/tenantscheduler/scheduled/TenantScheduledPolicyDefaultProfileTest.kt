package io.bluetape4k.workshop.leader.tenantscheduler.scheduled

import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyBeanPostProcessor
import io.bluetape4k.leader.spring.scheduling.LeaderScheduledPolicyRegistry
import io.bluetape4k.workshop.leader.tenantscheduler.TenantSchedulerLabApp
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.scheduling.support.ScheduledMethodRunnable

/** 기본 profile이 scheduled-policy infrastructure를 만들지 않는지 확인합니다. */
@SpringBootTest(classes = [TenantSchedulerLabApp::class])
class TenantScheduledPolicyDefaultProfileTest(
    private val applicationContext: ApplicationContext,
) {

    @Test
    fun `default profile has no scheduled policy infrastructure or fixture task`() {
        applicationContext.getBeansOfType<LeaderScheduledPolicyRegistry>().isEmpty().shouldBeTrue()
        applicationContext.getBeansOfType<LeaderScheduledPolicyBeanPostProcessor>().isEmpty().shouldBeTrue()
        applicationContext.getBeansOfType<ScheduledTaskHolder>().values
            .flatMap { it.scheduledTasks }
            .mapNotNull { it.task.runnable as? ScheduledMethodRunnable }
            .none { it.method.declaringClass == TenantScheduledPolicyFixture::class.java }
            .shouldBeTrue()
        applicationContext.environment.activeProfiles.contains("scheduled-policy").shouldBeFalse()
    }
}
