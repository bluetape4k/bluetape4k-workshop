package io.bluetape4k.workshop.exposed.virtualthread.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.virtualthread.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.virtualthread.domain.repository.ActorRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 영화 배우에 대한 정보를 제공하는 컨트롤러
 */
@RestController
class ActorController(
    private val actorRepo: ActorRepository,
) {
    companion object: KLogging()

    @GetMapping("/actors/{id}")
    fun getActorById(@PathVariable("id") actorId: Int): ActorDTO? {
        return actorRepo.findById(actorId).await()
    }

    @GetMapping("/actors")
    fun searchActors(request: HttpServletRequest): List<ActorDTO> {
        val params = request.parameterMap.map { it.key to it.value.firstOrNull() }.toMap()
        return actorRepo.searchActor(params).await()
    }

    @PostMapping("/actors")
    fun createActor(@RequestBody actor: ActorDTO): ActorDTO {
        return actorRepo.create(actor).await()
    }

    /**
     * [actorId]를 가지는 Actor를 삭제한다.
     *
     * @param actorId 삭제할 Actor의 id
     * @return 삭제된 Actor의 수
     */
    @DeleteMapping("/actors/{id}")
    fun deleteActor(@PathVariable("id") actorId: Int): Int {
        return actorRepo.deleteById(actorId).await()
    }
}
