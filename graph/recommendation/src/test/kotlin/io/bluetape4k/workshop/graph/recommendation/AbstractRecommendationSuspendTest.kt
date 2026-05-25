package io.bluetape4k.workshop.graph.recommendation

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.workshop.graph.recommendation.schema.PurchasedLabel
import io.bluetape4k.workshop.graph.recommendation.schema.UserLabel
import io.bluetape4k.workshop.graph.recommendation.seed.PROD_HEADPHONES
import io.bluetape4k.workshop.graph.recommendation.seed.PROD_KEYBOARD
import io.bluetape4k.workshop.graph.recommendation.seed.PROD_MOUSE
import io.bluetape4k.workshop.graph.recommendation.seed.USER_ALICE
import io.bluetape4k.workshop.graph.recommendation.seed.USER_BOB
import io.bluetape4k.workshop.graph.recommendation.seed.USER_DAVE
import io.bluetape4k.workshop.graph.recommendation.seed.USER_EVE
import io.bluetape4k.workshop.graph.recommendation.seed.seedRecommendation
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationSuspendService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Abstract test suite for [RecommendationSuspendService].
 *
 * Concrete subclasses supply [ops] and [service] backed by a specific graph backend.
 * Each test runs against a clean graph — [cleanGraph] drops and re-initializes before every test.
 *
 * ## Seed topology (see [seedRecommendation])
 * ```
 * PURCHASED (13 edges):
 *   alice   → laptop(5), phone(4), tablet(3)
 *   bob     → laptop(4), headphones(5)
 *   carol   → phone(5),  headphones(4)
 *   dave    → tablet(4), headphones(3)
 *   eve     → laptop(3), keyboard(5)
 *   frank   → phone(3),  mouse(4)
 *
 * FOLLOWS (12 edges):
 *   alice → bob, carol
 *   bob   → dave, carol
 *   carol → eve, bob
 *   dave  → frank, eve
 *   eve   → frank, dave
 *   frank → alice, bob
 * ```
 *
 * ## Expected results for alice
 * - recommendProducts: [headphones(3), keyboard(1), mouse(1)]
 * - recommendFollows:  [dave(1), eve(1)]
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRecommendationSuspendTest {

    protected abstract val graphName: String
    protected abstract val ops: GraphSuspendOperations
    protected abstract val service: RecommendationSuspendService

    @BeforeEach
    fun cleanGraph() = runTest {
        ops.dropGraph(graphName)
        service.initialize()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Vertex mutators
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `addUser creates User vertex with correct properties`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")

        alice.label shouldBeEqualTo "User"
        alice.properties[UserLabel.userId.name] shouldBeEqualTo USER_ALICE
        alice.properties[UserLabel.name.name] shouldBeEqualTo "Alice"
    }

    @Test
    fun `addUser returns existing vertex on second call (idempotent)`() = runTest {
        val first = service.addUser(USER_ALICE, "Alice")
        val second = service.addUser(USER_ALICE, "Alice")

        first.id shouldBeEqualTo second.id
    }

    @Test
    fun `addProduct creates Product vertex with correct properties`() = runTest {
        val laptop = service.addProduct("laptop", "Laptop", category = "Electronics")

        laptop.label shouldBeEqualTo "Product"
        laptop.properties["productId"] shouldBeEqualTo "laptop"
        laptop.properties["name"] shouldBeEqualTo "Laptop"
        laptop.properties["category"] shouldBeEqualTo "Electronics"
    }

    @Test
    fun `addProduct returns existing vertex on second call (idempotent)`() = runTest {
        val first = service.addProduct("laptop", "Laptop")
        val second = service.addProduct("laptop", "Laptop")

        first.id shouldBeEqualTo second.id
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Input validation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `addUser with blank userId throws IllegalArgumentException`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.addUser("", "Alice")
        }
    }

    @Test
    fun `addUser with blank name throws IllegalArgumentException`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.addUser(USER_ALICE, "")
        }
    }

    @Test
    fun `addProduct with blank productId throws IllegalArgumentException`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service.addProduct("", "Laptop")
        }
    }

    @Test
    fun `purchase with rating below 0 throws IllegalArgumentException`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")

        assertFailsWith<IllegalArgumentException> {
            service.purchase(alice.id, laptop.id, rating = -1)
        }
    }

    @Test
    fun `purchase with rating above 5 throws IllegalArgumentException`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")

        assertFailsWith<IllegalArgumentException> {
            service.purchase(alice.id, laptop.id, rating = 6)
        }
    }

    @Test
    fun `recommendProducts with limit 0 throws IllegalArgumentException`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.recommendProducts(alice.id, limit = 0)
        }
    }

    @Test
    fun `recommendProducts with limit above MAX throws IllegalArgumentException`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.recommendProducts(alice.id, limit = MAX_RECOMMENDATION_LIMIT + 1)
        }
    }

    @Test
    fun `recommendFollows with limit 0 throws IllegalArgumentException`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.recommendFollows(alice.id, limit = 0)
        }
    }

    @Test
    fun `recommendFollows with limit above MAX throws IllegalArgumentException`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.recommendFollows(alice.id, limit = MAX_RECOMMENDATION_LIMIT + 1)
        }
    }

    @Test
    fun `follow with same follower and followee throws IllegalArgumentException`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.follow(alice.id, alice.id)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Edge mutators
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `purchase creates PURCHASED edge without rating`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")
        val edge = service.purchase(alice.id, laptop.id)

        edge.label shouldBeEqualTo PurchasedLabel.label
        edge.startId shouldBeEqualTo alice.id
        edge.endId shouldBeEqualTo laptop.id
    }

    @Test
    fun `purchase creates PURCHASED edge with rating stored as String`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")
        val edge = service.purchase(alice.id, laptop.id, rating = 5)

        edge.properties[PurchasedLabel.rating.name] shouldBeEqualTo "5"
    }

    @Test
    fun `purchase creates PURCHASED edge with purchasedAt stored`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")
        val edge = service.purchase(alice.id, laptop.id, purchasedAt = "2024-01-15")

        edge.properties[PurchasedLabel.purchasedAt.name] shouldBeEqualTo "2024-01-15"
    }

    @Test
    fun `follow creates FOLLOWS edge between two users`() = runTest {
        val alice = service.addUser(USER_ALICE, "Alice")
        val bob = service.addUser(USER_BOB, "Bob")
        val edge = service.follow(alice.id, bob.id)

        edge.startId shouldBeEqualTo alice.id
        edge.endId shouldBeEqualTo bob.id
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. recommendProducts
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `recommendProducts returns headphones with score 3`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id)

        results.shouldNotBeEmpty()
        val headphones = results.first { it.product.properties["productId"] == PROD_HEADPHONES }
        headphones.score shouldBeEqualTo 3
    }

    @Test
    fun `recommendProducts top result is headphones`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id)

        results.first().product.properties["productId"] shouldBeEqualTo PROD_HEADPHONES
    }

    @Test
    fun `recommendProducts returns correct descending score ordering`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id)
        val productIds = results.map { it.product.properties["productId"]?.toString() }

        // headphones(3) > keyboard(1) = mouse(1); tie-break: keyboard < mouse alphabetically
        productIds shouldBeEqualTo listOf(PROD_HEADPHONES, PROD_KEYBOARD, PROD_MOUSE)
    }

    @Test
    fun `recommendProducts alice own products not in results`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id)
        val productIds = results.map { it.product.id }

        productIds shouldNotContain seed.laptop.id
        productIds shouldNotContain seed.phone.id
        productIds shouldNotContain seed.tablet.id
    }

    @Test
    fun `recommendProducts user with no purchases returns empty`() = runTest {
        val isolated = service.addUser("isolated", "Isolated User")

        val results = service.recommendProducts(isolated.id)

        results.shouldBeEmpty()
    }

    @Test
    fun `recommendProducts limit 1 returns only top result`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id, limit = 1)

        results shouldHaveSize 1
        results.first().product.properties["productId"] shouldBeEqualTo PROD_HEADPHONES
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. recommendFollows
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `recommendFollows returns dave and eve for alice`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val userIds = results.map { it.person.properties[UserLabel.userId.name] as? String }

        userIds shouldContain USER_DAVE
        userIds shouldContain USER_EVE
    }

    @Test
    fun `recommendFollows result is sorted by mutualFollowCount desc then userId asc`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val userIds = results.map { it.person.properties[UserLabel.userId.name] as? String }

        // dave(1) = eve(1); tie-break: dave < eve alphabetically
        userIds shouldBeEqualTo listOf(USER_DAVE, USER_EVE)
    }

    @Test
    fun `recommendFollows alice herself not in results`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val personIds = results.map { it.person.id }

        personIds shouldNotContain seed.alice.id
    }

    @Test
    fun `recommendFollows already-followed users not in results`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val personIds = results.map { it.person.id }

        // alice follows bob and carol — they must not appear in recommendations
        personIds shouldNotContain seed.bob.id
        personIds shouldNotContain seed.carol.id
    }

    @Test
    fun `recommendFollows user with no follows returns empty`() = runTest {
        val isolated = service.addUser("isolated", "Isolated User")

        val results = service.recommendFollows(isolated.id)

        results.shouldBeEmpty()
    }

    @Test
    fun `recommendFollows limit 1 returns only top result`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id, limit = 1)

        results shouldHaveSize 1
        results.first().person.properties[UserLabel.userId.name] shouldBeEqualTo USER_DAVE
    }

    @Test
    fun `recommendFollows mutual follow count is correct`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)

        results shouldHaveSize 2
        results.forEach { rec ->
            rec.mutualFollowCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `recommendFollows dave intermediary is bob`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val daveRec = results.first { it.person.properties[UserLabel.userId.name] == USER_DAVE }

        daveRec.mutualFollows.map { it.id } shouldContain seed.bob.id
    }

    @Test
    fun `recommendFollows eve intermediary is carol`() = runTest {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val eveRec = results.first { it.person.properties[UserLabel.userId.name] == USER_EVE }

        eveRec.mutualFollows.map { it.id } shouldContain seed.carol.id
    }
}
