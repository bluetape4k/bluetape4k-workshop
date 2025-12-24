package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.logging.coroutines.KLoggingChannel
import io.bluetape4k.logging.debug
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
import org.springframework.test.web.reactive.server.returnResult

class MemberControllerTest: AbstractVirtualThreadMvcTest() {

    companion object: KLoggingChannel()

    @Test
    fun `get all members`() = runTest {
        val members = webTestClient
            .get()
            .uri("/member")
            .exchange()
            .expectStatus().is2xxSuccessful
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
        val member = webTestClient
            .get()
            .uri("/member/1")
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<MemberDTO>().responseBody
            .awaitSingle()

        member.id shouldBeEqualTo 1L
    }

    @Test
    fun `search member`() = runTest {
        val condition = MemberSearchCondition(teamName = "teamA", ageGoe = 60)

        val members = webTestClient
            .post()
            .uri("/member/search")
            .bodyValue(condition)
            .exchange()
            .expectStatus().is2xxSuccessful
            .returnResult<MemberWithTeamDTO>().responseBody
            .asFlow()
            .toList()

        members.shouldNotBeEmpty()
        members.forEach {
            log.debug { "member: $it" }
        }
    }
}
