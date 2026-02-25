package io.bluetape4k.workshop.r2dbc.domain

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.workshop.r2dbc.AbstractR2dbcApplicationTest
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class MemberRepositoryTest(
    @param:Autowired private val memberRepository: MemberRepository,
): AbstractR2dbcApplicationTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        memberRepository.shouldNotBeNull()
    }

    @Test
    fun `save and get member`() = runTest {
        val member = Member(name = "John Doe", age = 30, email = "john@example.com")

        val savedMember = memberRepository.save(member)
        savedMember.shouldNotBeNull()
        savedMember.id.shouldNotBeNull()

        val loadedMember = memberRepository.findById(savedMember.id)!!
        loadedMember.name shouldBeEqualTo member.name
        loadedMember.age shouldBeEqualTo member.age
        loadedMember.email shouldBeEqualTo member.email
    }
}
