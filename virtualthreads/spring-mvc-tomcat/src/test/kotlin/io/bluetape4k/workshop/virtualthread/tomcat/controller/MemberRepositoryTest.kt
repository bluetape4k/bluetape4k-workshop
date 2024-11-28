package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.AbstractVirtualThreadMvcTest
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberDTO
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberSearchCondition
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberWithTeamDTO
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldNotBeNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.expectBody
import org.springframework.test.web.reactive.server.expectBodyList

class MemberRepositoryTest(
    @Autowired private val client: WebTestClient,
): AbstractVirtualThreadMvcTest() {

    companion object: KLogging()

    @Test
    fun `get all members`() {
        val members = client.get("/member")
            .expectBodyList<MemberDTO>().returnResult().responseBody!!

        members.shouldNotBeNull()
        members.forEach {
            log.debug { "member: $it" }
        }
    }

    @Test
    fun `get member by id`() {
        val member = client.get("/member/1")
            .expectBody<MemberDTO>().returnResult().responseBody!!

        member.id shouldBeEqualTo 1L
    }

    @Test
    fun `search member`() {
        val condition = MemberSearchCondition(teamName = "teamA", ageGoe = 60)

        val members = client.post("/member/search", condition)
            .expectBodyList<MemberWithTeamDTO>()
            .returnResult()
            .responseBody!!

        members.shouldNotBeNull()
        members.forEach {
            log.debug { "member: $it" }
        }
    }
}
