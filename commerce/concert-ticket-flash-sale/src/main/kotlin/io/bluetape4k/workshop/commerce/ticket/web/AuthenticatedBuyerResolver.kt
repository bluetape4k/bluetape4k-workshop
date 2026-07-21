package io.bluetape4k.workshop.commerce.ticket.web

import org.springframework.security.core.Authentication
import java.util.UUID

/** Maps an authenticated principal to the stable internal subject ID used by persistence. */
fun interface AuthenticatedBuyerResolver {
    fun resolve(authentication: Authentication): UUID
}

class PrincipalSubjectResolver : AuthenticatedBuyerResolver {
    override fun resolve(authentication: Authentication): UUID =
        runCatching { UUID.fromString(authentication.name) }
            .getOrElse { throw InvalidAuthenticatedSubject() }
}

class InvalidAuthenticatedSubject : IllegalArgumentException("invalid_authenticated_subject")
