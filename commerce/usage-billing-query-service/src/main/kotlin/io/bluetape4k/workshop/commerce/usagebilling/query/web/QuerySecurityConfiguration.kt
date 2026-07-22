package io.bluetape4k.workshop.commerce.usagebilling.query.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration(proxyBeanMethods = false)
class QuerySecurityConfiguration {
    @Bean
    fun querySecurity(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/actuator/metrics/**", "/api/v1/operator/**").hasRole("OPERATOR")
                .requestMatchers("/api/v1/tenants/**").authenticated()
                .anyRequest().denyAll()
        }
        .httpBasic(withDefaults())
        .build()
}
