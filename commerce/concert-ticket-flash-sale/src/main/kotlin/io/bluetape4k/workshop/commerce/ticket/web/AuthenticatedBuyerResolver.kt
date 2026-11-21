package io.bluetape4k.workshop.commerce.ticket.web

import org.springframework.security.core.Authentication
import java.util.*

/** 인증된 principal을 persistence가 사용하는 안정적인 internal subject ID로 매핑합니다. */
/** 인증된 principal을 persistence가 사용하는 안정적인 internal subject ID로 매핑합니다. */
fun interface AuthenticatedBuyerResolver {
    /**
     * resolve은 콘서트 티켓 판매의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param authentication 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    fun resolve(authentication: Authentication): UUID
}

/**
 * PrincipalSubjectResolver은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의
 * 규칙에
 * 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class PrincipalSubjectResolver: AuthenticatedBuyerResolver {
    /**
     * resolve은 콘서트 티켓 판매의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
     *
     * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
     *
     * @param authentication 명령을 수행한 주체 또는 lease 소유자입니다. 권한·감사·동시성 검증에 사용합니다.
     */
    override fun resolve(authentication: Authentication): UUID =
        runCatching {
            UUID.fromString(authentication.name)
        }.getOrElse {
            throw InvalidAuthenticatedSubject()
        }
}

/**
 * InvalidAuthenticatedSubject은 콘서트 티켓 플래시 세일 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이
 * 타입의
 * 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
class InvalidAuthenticatedSubject: IllegalArgumentException("invalid_authenticated_subject")
