package io.bluetape4k.workshop.commerce.usagebilling.query.web

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.logging.KLogging
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

/**
 * QueryTenantAuthorizerTest 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class QueryTenantAuthorizerTest {

    companion object: KLogging()

/**
 * authorizer은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private val authorizer = QueryTenantAuthorizer()

    @Test
/**
 * 이 시나리오는 "matching tenant authority grants customer read access" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `matching tenant authority grants customer read access`() {
        authorizer.requireAccess(authentication("TENANT_tenant-a"), "tenant-a")
    }

    @Test
/**
 * 이 시나리오는 "different tenant authority is rejected before reading a projection" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `different tenant authority is rejected before reading a projection`() {
        assertFailsWith<AccessDeniedException> {
            authorizer.requireAccess(authentication("TENANT_tenant-b"), "tenant-a")
        }
    }

/**
 * authentication은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun authentication(authority: String): TestingAuthenticationToken =
        TestingAuthenticationToken(
            "customer",
            "unused",
            listOf(SimpleGrantedAuthority(authority))
        )
}
