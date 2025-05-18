package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
import io.bluetape4k.spring.tests.httpGet
import io.bluetape4k.spring.tests.httpPost
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberDTO
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberSearchCondition
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberWithTeamDTO
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeEmpty
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult

class MemberRepositoryTest(
    @Autowired private val client: WebTestClient,
): AbstractVirtualThreadMvcTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get all members`() = runTest {
        val members = client.httpGet("/member")
            .returnResult<MemberDTO>().responseBody
            .asFlow()
            .toList()

        members.shouldNotBeNull()
        members.forEach {
            log.debug { "member: $it" }
        }
    }

    @Test
    fun `get member by id`() = runTest {
        val member = client.httpGet("/member/1")
            .returnResult<MemberDTO>().responseBody
            .awaitSingle()

        member.id shouldBeEqualTo 1L
    }

    @Test
    fun `search member`() = runTest {
        val condition = MemberSearchCondition(teamName = "teamA", ageGoe = 60)

        val members = client.httpPost("/member/search", condition)
            .returnResult<MemberWithTeamDTO>().responseBody
            .asFlow()
            .toList()

        members.shouldNotBeEmpty()
        members.forEach {
            log.debug { "member: $it" }
        }
    }
}
