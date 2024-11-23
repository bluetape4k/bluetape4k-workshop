package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberDTO
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberSearchCondition
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.MemberWithTeamDTO
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.toMemberDTO
import io.bluetape4k.workshop.virtualthread.tomcat.domain.repository.MemberRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/member")
class MemberController(private val memberRepo: MemberRepository) {

    companion object: KLogging()

    @GetMapping("", "/")
    fun getAllMembers(): List<MemberDTO> {
        // virtualFuture 를 명시적으로 사용하지 않더라도, TomcatConfig에서 설정한 virtualThreadExecutor를 사용한다.
        return virtualFuture {
            log.debug { "Find all members ..." }
            memberRepo.findAll().map { it.toMemberDTO() }
        }.await()
    }

    @GetMapping("/{id}")
    fun getMemberById(@PathVariable("id") id: Long): MemberDTO? {
        return memberRepo.findByIdOrNull(id)?.toMemberDTO()
    }

    @PostMapping("/search")
    fun searchMember(@RequestBody condition: MemberSearchCondition): List<MemberWithTeamDTO> {
        return memberRepo.search(condition)
    }
}
