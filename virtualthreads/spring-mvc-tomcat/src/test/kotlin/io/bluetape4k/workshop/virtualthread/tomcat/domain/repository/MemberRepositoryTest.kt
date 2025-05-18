package io.bluetape4k.workshop.virtualthread.tomcat.domain.repository

import io.bluetape4k.concurrent.virtualthread.awaitAll
import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.domain.AbstractDomainTest
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberSearchCondition
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.toMemberDTO
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldHaveSize
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull

class MemberRepositoryTest(
    @Autowired private val memberRepo: MemberRepository,
): AbstractDomainTest() {

    companion object: KLoggingChannel()

    @Test
    fun `context loading`() {
        memberRepo.shouldNotBeNull()
    }

    @Test
    fun `find member by id`() {
        val memberId = 42L
        val task = virtualFuture {
            memberRepo.findByIdOrNull(memberId)
        }
        val member = task.await()
        log.debug { "member=${member?.toMemberDTO()}" }
        member.shouldNotBeNull()
        member.id shouldBeEqualTo memberId
    }

    @Test
    fun `find members by id with multiple virtual threads`() {
        val taskCount = 10
        val tasks = List(taskCount) { index ->
            virtualFuture {
                memberRepo.findByIdOrNull((index + 5L))
            }
        }
        val members = tasks.awaitAll()
        members shouldHaveSize taskCount
    }

    @Test
    fun `search by condition`() {
        val searchCondition = MemberSearchCondition(teamName = "teamA", ageGoe = 50)

        val memberTeamDtos = virtualFuture {
            memberRepo.search(searchCondition)
        }.await()

        memberTeamDtos.shouldNotBeEmpty()
        memberTeamDtos.forEach {
            log.debug { it }
        }
    }
}
