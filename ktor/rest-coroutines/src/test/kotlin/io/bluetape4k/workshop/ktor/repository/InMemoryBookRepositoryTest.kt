package io.bluetape4k.workshop.ktor.repository

import io.bluetape4k.workshop.ktor.AbstractKtorTest
import io.bluetape4k.workshop.ktor.domain.Book
import io.bluetape4k.workshop.ktor.domain.DomainError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.assertions.shouldNotBeNull
import org.junit.jupiter.api.Test
import io.bluetape4k.assertions.assertFailsWith

class InMemoryBookRepositoryTest : AbstractKtorTest() {

    private fun repo() = InMemoryBookRepository()

    private val book1 = Book("b-1", "Kotlin in Action", "Jemerov", 2017)
    private val book2 = Book("b-2", "Clean Code", "Martin", 2008)

    @Test
    fun `save and findById returns saved book`() = runTest {
        val repo = repo()
        repo.save(book1)

        val found = repo.findById(book1.id)
        found shouldBeEqualTo book1
    }

    @Test
    fun `findById returns null for unknown id`() = runTest {
        val repo = repo()
        repo.findById("no-such-id").shouldBeNull()
    }

    @Test
    fun `findAll returns all saved books`() = runTest {
        val repo = repo()
        repo.save(book1)
        repo.save(book2)

        val all = repo.findAll()
        all.size shouldBeEqualTo 2
        all.any { it.id == book1.id } shouldBeEqualTo true
        all.any { it.id == book2.id } shouldBeEqualTo true
    }

    @Test
    fun `save throws DomainError Conflict on duplicate id`() = runTest {
        val repo = repo()
        repo.save(book1)

        assertFailsWith<DomainError.Conflict> {
            repo.save(book1.copy(title = "Different Title"))
        }
    }

    @Test
    fun `update replaces book content`() = runTest {
        val repo = repo()
        repo.save(book1)

        val updated = repo.update(book1.id, book1.copy(title = "Updated Title"))
        updated.title shouldBeEqualTo "Updated Title"
        repo.findById(book1.id)?.title shouldBeEqualTo "Updated Title"
    }

    @Test
    fun `update throws DomainError NotFound for unknown id`() = runTest {
        val repo = repo()

        assertFailsWith<DomainError.NotFound> {
            repo.update("no-such-id", book1)
        }
    }

    @Test
    fun `delete removes book`() = runTest {
        val repo = repo()
        repo.save(book1)
        repo.delete(book1.id)

        repo.findById(book1.id).shouldBeNull()
    }

    @Test
    fun `delete throws DomainError NotFound for unknown id`() = runTest {
        val repo = repo()

        assertFailsWith<DomainError.NotFound> {
            repo.delete("no-such-id")
        }
    }

    @Test
    fun `stream emits book after save`() = runTest {
        val repo = repo()
        val received = Channel<Book>(Channel.BUFFERED)

        // runTest 가 TestScope 를 제공하므로 launch {} 를 사용할 수 있습니다.
        val job = launch { repo.stream().collect { received.send(it) } }
        delay(50) // let subscriber register on hot SharedFlow

        repo.save(book1)

        val result = withTimeoutOrNull(2_000) { received.receive() }
        result.shouldNotBeNull()
        result shouldBeEqualTo book1

        job.cancel()
        received.close()
    }

    @Test
    fun `stream emits book after update`() = runTest {
        val repo = repo()
        repo.save(book1)

        val received = Channel<Book>(Channel.BUFFERED)
        val job = launch { repo.stream().collect { received.send(it) } }
        delay(50)

        val updated = repo.update(book1.id, book1.copy(year = 2025))

        val result = withTimeoutOrNull(2_000) { received.receive() }
        result.shouldNotBeNull()
        result?.year shouldBeEqualTo 2025
        result shouldBeEqualTo updated

        job.cancel()
        received.close()
    }
}
