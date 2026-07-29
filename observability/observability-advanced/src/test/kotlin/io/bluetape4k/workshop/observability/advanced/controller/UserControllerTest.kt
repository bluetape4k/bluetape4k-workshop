package io.bluetape4k.workshop.observability.advanced.controller

import io.bluetape4k.junit5.coroutines.runSuspendIO
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.workshop.observability.advanced.AbstractAdvancedTest
import io.bluetape4k.workshop.observability.advanced.TestObservationConfig
import io.bluetape4k.workshop.observability.advanced.model.User
import io.bluetape4k.workshop.observability.advanced.repository.UserCacheRepository
import io.bluetape4k.workshop.observability.advanced.repository.UserRepository
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

@Import(TestObservationConfig::class)
class UserControllerTest : AbstractAdvancedTest() {

    @Autowired
    private lateinit var testRegistry: TestObservationRegistry

    @Autowired
    private lateinit var repo: UserRepository

    @Autowired
    private lateinit var cache: UserCacheRepository

    // C17: @BeforeEach suspend setup 은 runSuspendIO {} 를 사용해야 합니다.
    @BeforeEach
    fun setup() = runSuspendIO {
        testRegistry.clear()
        repo.deleteAll()
    }

    @AfterEach
    fun assertNoLeakedObservation() {
        TestObservationRegistryAssert.assertThat(testRegistry)
            .doesNotHaveAnyRemainingCurrentObservation()
    }

    @Test
    fun `POST users - creates user and instruments user service create span`() = runSuspendIO {
        val newUser = User(id = 2001L, name = "bob", email = "bob@example.com")

        webTestClient.post()
            .uri("/users")
            .bodyValue(newUser)
            .exchange()
            .expectStatus().isCreated

        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.service.create")
            .that().hasBeenStarted().hasBeenStopped()
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.db.save")
            .that().hasBeenStarted().hasBeenStopped()
    }

    @Test
    fun `GET users id - cache miss instruments user db find span`() = runSuspendIO {
        val newUser = User(id = 2002L, name = "carol", email = "carol@example.com")
        // 먼저 user 를 생성합니다. POST 는 cache 도 warm 하므로 GET 에서 miss 를 강제하기 위해 cache 를 비웁니다.
        webTestClient.post()
            .uri("/users")
            .bodyValue(newUser)
            .exchange()
            .expectStatus().isCreated

        cache.delete(newUser.id)
        testRegistry.clear()

        val body = webTestClient.get()
            .uri("/users/${newUser.id}")
            .exchange()
            .expectStatus().isOk
            .expectBody(User::class.java)
            .returnResult()
            .responseBody

        body.shouldNotBeNull() shouldBeEqualTo newUser
        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasObservationWithNameEqualTo("user.db.find")
            .that().hasBeenStarted().hasBeenStopped()
    }

    @Test
    fun `GET users id - cache hit skips user db find span`() = runSuspendIO {
        val newUser = User(id = 2003L, name = "dave", email = "dave@example.com")
        // user 를 생성합니다.
        webTestClient.post()
            .uri("/users")
            .bodyValue(newUser)
            .exchange()
            .expectStatus().isCreated

        // 첫 번째 GET 은 cache 를 채웁니다.
        webTestClient.get()
            .uri("/users/${newUser.id}")
            .exchange()
            .expectStatus().isOk

        testRegistry.clear()

        // 두 번째 GET 은 cache hit 이어야 하며 DB span 이 없어야 합니다.
        webTestClient.get()
            .uri("/users/${newUser.id}")
            .exchange()
            .expectStatus().isOk

        TestObservationRegistryAssert.assertThat(testRegistry)
            .hasNumberOfObservationsWithNameEqualTo("user.db.find", 0)
    }
}
