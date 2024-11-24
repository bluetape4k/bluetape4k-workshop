package io.bluetape4k.workshop.exposed.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.exposed.domain.dto.ActorDTO
import io.bluetape4k.workshop.exposed.domain.respository.ActorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * 영화와 영화 배우에 대한 정보를 제공하는 컨트롤러
 */
@RestController
class ActorController(
    private val actorRepo: ActorRepository,
): CoroutineScope by CoroutineScope(Dispatchers.IO + SupervisorJob()) {

    companion object: KLogging()

    @GetMapping("/actors/{id}")
    suspend fun getActorById(@PathVariable("id") actorId: Int): ActorDTO? {
        return actorRepo.findById(actorId)
    }

    @GetMapping("/actors")
    suspend fun searchActors(request: ServerHttpRequest): List<ActorDTO> {
        val params = request.queryParams.map { it.key to it.value.first() }.toMap()
        return actorRepo.searchActor(params)
    }

    @PostMapping("/actors")
    suspend fun createActor(@RequestBody actor: ActorDTO): ActorDTO {
        return actorRepo.create(actor)
    }

    /**
     * [actorId]를 가지는 Actor를 삭제한다.
     *
     * @param actorId 삭제할 Actor의 id
     * @return 삭제된 Actor의 수
     */
    @DeleteMapping("/actors/{id}")
    suspend fun deleteActor(@PathVariable("id") actorId: Int): Int {
        return actorRepo.deleteById(actorId)
    }
}
