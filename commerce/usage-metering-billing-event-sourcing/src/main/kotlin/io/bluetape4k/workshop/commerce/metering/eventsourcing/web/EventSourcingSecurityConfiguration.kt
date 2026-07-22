package io.bluetape4k.workshop.commerce.metering.eventsourcing.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration(proxyBeanMethods = false)
class EventSourcingSecurityConfiguration {
    @Bean
    fun eventSourcingSecurity(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/api/admin/event-sourcing/**").hasRole("OPERATOR")
                .requestMatchers("/api/v1/tenants/*/meters/**").hasAuthority("TENANT_BILLING_WRITE")
                .requestMatchers("/api/v1/tenants/*/billing/**").hasAuthority("TENANT_BILLING_READ")
                .anyRequest().denyAll()
        }
        .httpBasic(withDefaults())
        .build()
}
