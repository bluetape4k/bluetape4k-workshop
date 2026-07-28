package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * customer compatibility endpoint는 adapter가 검증한 bounded workshop identity header를 사용합니다.
 * operator route는 [EventSourcedOperatorAccessFilter]가 독립적으로 fence 처리합니다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventSourcedOperatorProperties::class)
internal class EventSourcedSecurityConfiguration {

    @Bean
    fun eventSourcedSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { csrf -> csrf.disable() }
            .authorizeHttpRequests { requests -> requests.anyRequest().permitAll() }
            .httpBasic { basic -> basic.disable() }
            .formLogin { form -> form.disable() }
            .build()
}
