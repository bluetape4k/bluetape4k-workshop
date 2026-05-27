package io.bluetape4k.workshop.observability.advanced.service

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeNull
import io.bluetape4k.workshop.observability.advanced.AbstractAdvancedTest
import io.bluetape4k.workshop.observability.advanced.TestObservationConfig
import io.bluetape4k.workshop.observability.advanced.model.User
import io.bluetape4k.workshop.observability.advanced.repository.UserCacheRepository
import io.bluetape4k.workshop.observability.advanced.repository.UserRepository
import io.micrometer.observation.tck.ObservationContextAssert
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

@Import(TestObservationConfig::class)
class UserServiceTest : AbstractAdvancedTest() {

    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var repo: UserRepository

    @Autowired
    private lateinit var cache: UserCacheRepository

    @Autowired
    private lateinit var testRegistry: TestObservationRegistry

    private val testUser = User(id = 1001L, name = "alice", email = "alice@example.com")

    // C17: @BeforeEach suspend setup must use runSuspendIO {} (blocking wrapper, not suspend fun)
    @BeforeEach
    fun setup() = runSuspendIO {
        testRegistry.clear()
        repo.deleteAll()
        cache.delete(testUser.id)
    }

    @AfterEach
    fun assertNoLeakedObservation() {
        TestObservationRegistryAssert.assertThat(testRegistry)
            .doesNotHaveAnyRemainingCurrentObservation()
    }

    @Test
    fun `getById - cache miss instruments expected spans`() = runSuspendIO {
        repo.save(testUser)

        val result = userService.getById(testUser.id)

        result shouldBeEqualTo testUser
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.get")
            .that().hasBeenStarted().hasBeenStopped()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.cache.get")
            .that().hasBeenStarted().hasBeenStopped()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.db.find")
            .that().hasBeenStarted().hasBeenStopped()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.cache.put")
            .that().hasBeenStarted().hasBeenStopped()
        assertParentObservation("user.cache.get", "user.service.get")
        assertParentObservation("user.db.find", "user.service.get")
        assertParentObservation("user.cache.put", "user.service.get")
    }

    @Test
    fun `getById - cache hit skips db span`() = runSuspendIO {
        cache.put(testUser)
        testRegistry.clear()

        val result = userService.getById(testUser.id)

        result shouldBeEqualTo testUser
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.get")
            .that().hasBeenStarted().hasBeenStopped()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.cache.get")
            .that().hasBeenStarted().hasBeenStopped()
        assertParentObservation("user.cache.get", "user.service.get")
        // DB span must NOT be present on cache hit
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasNumberOfObservationsWithNameEqualTo("user.db.find", 0)
    }

    @Test
    fun `getById - returns null for non-existent user`() = runSuspendIO {
        val result = userService.getById(99999L)

        result.shouldBeNull()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.get")
            .that().hasBeenStarted().hasBeenStopped()
    }

    @Test
    fun `create - produces user service create and user db save observations`() = runSuspendIO {
        val result = userService.create(testUser)

        result shouldBeEqualTo testUser
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.create")
            .that().hasBeenStarted().hasBeenStopped()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.db.save")
            .that().hasBeenStarted().hasBeenStopped()
        assertParentObservation("user.db.save", "user.service.create")
    }

    @Test
    fun `getById - explicit cache delete forces DB lookup`() = runSuspendIO {
        // Arrange: user in DB, no cache entry
        repo.save(testUser)
        cache.delete(testUser.id)
        testRegistry.clear()

        val result = userService.getById(testUser.id)

        // DB fallback succeeds and result is non-null
        result shouldBeEqualTo testUser
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.get")
            .that().hasBeenStarted().hasBeenStopped()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.db.find")
            .that().hasBeenStarted().hasBeenStopped()
        // After DB fetch, result is also written to cache
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.cache.put")
            .that().hasBeenStarted().hasBeenStopped()
    }

    private fun assertParentObservation(childName: String, parentName: String) {
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo(childName)
            .that()
            .hasParentObservationContextSatisfying { parent ->
                ObservationContextAssert.assertThat(parent).hasNameEqualTo(parentName)
            }
    }
}
