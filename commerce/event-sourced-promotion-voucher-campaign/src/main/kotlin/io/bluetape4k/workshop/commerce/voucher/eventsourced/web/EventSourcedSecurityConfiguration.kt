package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * Customer compatibility endpoints use the bounded workshop identity headers validated by their
 * adapters. Operator routes are fenced independently by [EventSourcedOperatorAccessFilter].
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
