package io.bluetape4k.workshop.leader.tenantscheduler.scheduled

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `scheduled-policy` profile에서만 Spring scheduling 예제를 활성화합니다.
 *
 * leader registry와 scheduler executor는 bluetape4k와 Spring Boot가 소유하므로
 * 예제 configuration은 fixture bean과 scheduling 활성화만 선언합니다. 이 consumer
 * 예제에서는 upstream aspect bean을 Spring runtime proxy로 연결하기 위해
 * `@EnableAspectJAutoProxy`를 명시하고, profile YAML의 `spring.aop.auto=false`로
 * Boot의 중복 proxy creator를 막습니다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("scheduled-policy")
@EnableScheduling
@EnableAspectJAutoProxy(proxyTargetClass = true)
class TenantScheduledPolicyConfiguration {

    @Bean
    fun tenantScheduledPolicyFixture(): TenantScheduledPolicyFixture = TenantScheduledPolicyFixture()
}
