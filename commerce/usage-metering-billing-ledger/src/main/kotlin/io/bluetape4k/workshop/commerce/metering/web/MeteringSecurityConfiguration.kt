package io.bluetape4k.workshop.commerce.metering.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration(proxyBeanMethods = false)
class MeteringSecurityConfiguration {
    @Bean
    fun meteringSecurity(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/info").permitAll()
                    .requestMatchers("/api/v1/operator/**").hasRole("OPERATOR")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().denyAll()
            }
            .httpBasic(withDefaults())
            .build()
}
