package io.bluetape4k.workshop.spring.security.mvc.config

import io.bluetape4k.logging.KLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    companion object: KLogging()

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        // HttpSecurityDsl 을 사용합니다.
        http {
            authorizeHttpRequests {
                authorize("/", permitAll)
                authorize("/css/**", permitAll)
                authorize("/user/**", hasAuthority("ROLE_USER"))
            }
            formLogin {
                loginPage = "/log-in"
            }
        }
        return http.build()
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun userDetailsService(passwordEncoder: PasswordEncoder): UserDetailsService {
        val userDetails = User.builder()
            .username("user")
            .password("password")
            .roles("USER")
            .passwordEncoder { rawPassword -> passwordEncoder.encode(rawPassword) }
            .build()

        return InMemoryUserDetailsManager(userDetails)
    }
}
