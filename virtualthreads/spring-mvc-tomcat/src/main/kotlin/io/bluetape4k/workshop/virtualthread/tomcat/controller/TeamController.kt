package io.bluetape4k.workshop.virtualthread.tomcat.controller

import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.TeamDTO
import io.bluetape4k.workshop.virtualthread.tomcat.domain.dto.toTeamDTO
import io.bluetape4k.workshop.virtualthread.tomcat.domain.repository.TeamRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/team")
class TeamController(private val teamRepo: TeamRepository) {

    companion object: KLogging()

    @GetMapping("", "/")
    fun getAllTeams(): List<TeamDTO> {
        // virtualFuture 를 명시적으로 사용하지 않더라도, TomcatConfig에서 설정한 virtualThreadExecutor를 사용한다.
        return virtualFuture {
            log.debug { "Find all teams ..." }
            teamRepo.findAll().map { it.toTeamDTO() }
        }.await()
    }

    @GetMapping("/{id}")
    fun findById(@PathVariable("id") teamId: Long): TeamDTO? {
        return teamRepo.findByIdOrNull(teamId)?.toTeamDTO()
    }

    @GetMapping("/name/{name}")
    fun findByName(@PathVariable("name") name: String): TeamDTO? {
        return teamRepo.findByName(name)?.toTeamDTO()
    }
}
