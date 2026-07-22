package io.bluetape4k.workshop.commerce.usagebilling.query.web

import io.bluetape4k.assertions.assertFailsWith
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.authority.SimpleGrantedAuthority

class QueryTenantAuthorizerTest {
    private val authorizer = QueryTenantAuthorizer()

    @Test
    fun `matching tenant authority grants customer read access`() {
        authorizer.requireAccess(authentication("TENANT_tenant-a"), "tenant-a")
    }

    @Test
    fun `different tenant authority is rejected before reading a projection`() {
        assertFailsWith<AccessDeniedException> {
            authorizer.requireAccess(authentication("TENANT_tenant-b"), "tenant-a")
        }
    }

    private fun authentication(authority: String) =
        TestingAuthenticationToken("customer", "unused", listOf(SimpleGrantedAuthority(authority)))
}
