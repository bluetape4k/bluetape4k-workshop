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
import java.util.*

@Configuration(proxyBeanMethods = false)
/**
 * TicketSecurityConfiguration은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
internal class TicketSecurityConfiguration {
    @Bean
    /**
     * authenticatedBuyerResolver은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을
     * 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     */
    fun authenticatedBuyerResolver(): AuthenticatedBuyerResolver = PrincipalSubjectResolver()

    /** deployment가 upstream에서 authenticated principal을 제공할 때까지 production은 fail-closed로 유지됩니다. */
    /** deployment가 upstream에서 authenticated principal을 제공할 때까지 production은 fail-closed로 유지됩니다. */
    @Bean
    @Profile("!demo")
    fun productionTicketSecurity(http: HttpSecurity): SecurityFilterChain = baseSecurity(http).build()

    /** header identity는 loopback demo 실행으로 의도적으로 격리합니다. */
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

    /**
     * baseSecurity은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param http 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
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

/**
 * DemoBuyerFilter은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰
 * 해석합니다.
 *
 * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 */
private class DemoBuyerFilter: OncePerRequestFilter() {
    /**
     * doFilterInternal은 콘서트 티켓 플래시 세일 업무 규칙을 적용해 입력 검증, 상태 전이, 영속화 및 후속 이벤트 처리의 일관성을 보장합니다.
     *
     * 호출자는 저장소의 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     * @param request 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param response 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     * @param chain 업무 입력입니다. 필수값·권한·멱등성 범위를 검증한 뒤 부작용을 시작합니다.
     */
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
