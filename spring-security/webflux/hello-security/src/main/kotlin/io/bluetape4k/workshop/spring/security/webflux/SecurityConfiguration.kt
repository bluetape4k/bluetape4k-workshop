package io.bluetape4k.workshop.spring.security.webflux

import io.bluetape4k.logging.coroutines.KLoggingChannel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.server.SecurityWebFilterChain

@Configuration
@EnableWebFluxSecurity
class SecurityConfiguration {

    companion object: KLoggingChannel()

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http {
            authorizeExchange {
                authorize("/log-in", permitAll)
                authorize("/", permitAll)
                authorize("/css/**", permitAll)
                authorize("/user/**", hasAuthority("ROLE_USER"))
            }
            formLogin {
                loginPage = "/log-in"
            }
        }
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun userDetailsService(passwordEncoder: PasswordEncoder): ReactiveUserDetailsService {
        val userDetails = User.builder()
            .username("user")
            .password("password")
            .roles("USER")
            .passwordEncoder { rawPassword -> passwordEncoder.encode(rawPassword) }
            .build()

        return MapReactiveUserDetailsService(userDetails)
    }
}
