package io.bluetape4k.workshop.commerce.voucher.eventsourced.web

import io.bluetape4k.logging.KLogging
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

    companion object: KLogging()

    /**
     * eventSourcedSecurityFilterChain은 프로모션 바우처의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무
     * 오류를 계약된
     * 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param http 업무 처리에 필요한 http 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
     */
    @Bean
    fun eventSourcedSecurityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { requests ->
                requests.anyRequest().permitAll()
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .build()
}
