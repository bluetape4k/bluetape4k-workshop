package io.bluetape4k.workshop.commerce.ticket.web

import org.springframework.security.core.Authentication
import java.util.UUID

/** 인증된 principal을 persistence가 사용하는 안정적인 internal subject ID로 매핑합니다. */
fun interface AuthenticatedBuyerResolver {
    fun resolve(authentication: Authentication): UUID
}

class PrincipalSubjectResolver : AuthenticatedBuyerResolver {
    override fun resolve(authentication: Authentication): UUID =
        runCatching { UUID.fromString(authentication.name) }
            .getOrElse { throw InvalidAuthenticatedSubject() }
}

class InvalidAuthenticatedSubject : IllegalArgumentException("invalid_authenticated_subject")
