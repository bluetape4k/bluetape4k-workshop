package io.bluetape4k.workshop.exposed.r2dbc.domain

import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository

@Repository
interface MemberRepository: CoroutineCrudRepository<Member, Long> {

}
