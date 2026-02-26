package io.bluetape4k.workshop.problem.controller

import io.bluetape4k.logging.KLogging
import io.bluetape4k.workshop.problem.exceptions.ExampleException
import io.bluetape4k.workshop.problem.exceptions.InvalidTaskIdException
import io.bluetape4k.workshop.problem.exceptions.TaskNotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.Serializable
import java.nio.file.AccessDeniedException

@RestController
@RequestMapping("/tasks")
class TaskController {

    data class Task(val id: Long, val name: String): Serializable

    companion object: KLogging() {
        private val tasks = mapOf(
            1L to Task(1L, "My first task"),
            2L to Task(2L, "My second task")
        )
    }

    @GetMapping
    suspend fun getTasks(): Flow<Task> = tasks.values.asFlow()

    @GetMapping("/{id}")
    suspend fun getTaskById(@PathVariable id: Long): Task? {
        if (id <= 0) {
            throw InvalidTaskIdException(id)
        }
        return tasks[id] ?: throw TaskNotFoundException(id)
    }

    @GetMapping("/find/{id}")
    suspend fun findTaskById(@PathVariable id: Long): Task? {
        if (id <= 0) {
            throw InvalidTaskIdException(id)
        }
        return tasks[id] ?: throw TaskNotFoundException(id)
    }

    @PutMapping("/{id}")
    suspend fun updateTask(@PathVariable id: Long) {
        // HttpStatus.NOT_IMPLEMENTED (501) 발생 
        throw UnsupportedOperationException("구현 중", ExampleException("Boom!"))
    }

    @DeleteMapping("/{id}")
    suspend fun deleteTask(@PathVariable id: Long) {
        throw AccessDeniedException("You can't delete this task [$id]")
    }
}
