package io.bluetape4k.workshop.commerce.ticket.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Configuration(proxyBeanMethods = false)
internal class TicketSecurityConfiguration {
    @Bean
    fun authenticatedBuyerResolver(): AuthenticatedBuyerResolver = PrincipalSubjectResolver()

    /** deployment가 upstream에서 authenticated principal을 제공할 때까지 production은 fail-closed로 유지됩니다. */
    @Bean
    @Profile("!demo")
    fun productionTicketSecurity(http: HttpSecurity): SecurityFilterChain = baseSecurity(http).build()

    /** header identity는 loopback demo 실행으로 의도적으로 격리합니다. */
    @Bean
    @Profile("demo")
    fun demoTicketSecurity(
        http: HttpSecurity,
        @Value("\${workshop.ticket.demo-operator-token}") operatorToken: String,
    ): SecurityFilterChain = baseSecurity(http)
        .addFilterBefore(DemoBuyerFilter(), UsernamePasswordAuthenticationFilter::class.java)
        .addFilterBefore(OperatorAccessFilter(operatorToken), UsernamePasswordAuthenticationFilter::class.java)
        .build()

    private fun baseSecurity(http: HttpSecurity): HttpSecurity = http
        .csrf { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers("/", "/index.html", "/app.js", "/styles.css", "/actuator/health/**").permitAll()
                .requestMatchers("/api/v1/operator/**").hasRole("OPERATOR")
                .anyRequest().authenticated()
        }
        .exceptionHandling {
            it.authenticationEntryPoint { _, response, _ -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED) }
                .accessDeniedHandler { _, response, _ -> response.sendError(HttpServletResponse.SC_FORBIDDEN) }
        }
        .addFilterAfter(RequestLoggingFilter(), UsernamePasswordAuthenticationFilter::class.java)
}

private class DemoBuyerFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val raw = request.getHeader("X-Demo-Buyer")
        if (raw != null) {
            if (!request.remoteAddr.isLoopbackLiteral()) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                return
            }
            val subject = runCatching { UUID.fromString(raw) }.getOrNull()
            if (subject == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST)
                return
            }
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken.authenticated(subject.toString(), null, emptyList())
        }
        chain.doFilter(request, response)
    }
}
