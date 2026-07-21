package io.bluetape4k.workshop.leader.jobsafety.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class JobSafetySecurityConfiguration {
    @Bean
    fun jobSafetySecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .httpBasic(withDefaults())
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/job-safety/scenarios").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/job-safety/scenarios/*/run").authenticated()
                    .requestMatchers(
                        "/api/job-safety/scenarios/*/reset",
                        "/api/job-safety/effects/**",
                        "/api/job-safety/unsafe/**",
                    ).hasRole(OPERATOR_ROLE)
                    .anyRequest().denyAll()
            }.build()

    companion object {
        const val OPERATOR_ROLE: String = "JOB_SAFETY_OPERATOR"
    }
}
