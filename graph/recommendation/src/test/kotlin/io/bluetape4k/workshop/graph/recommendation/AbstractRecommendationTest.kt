package io.bluetape4k.workshop.graph.recommendation

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldContainAll
import io.bluetape4k.assertions.shouldHaveSize
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.graph.model.GraphElementId
import io.bluetape4k.graph.repository.GraphOperations
import io.bluetape4k.workshop.graph.recommendation.model.FollowRecommendation
import io.bluetape4k.workshop.graph.recommendation.model.ProductRecommendation
import io.bluetape4k.workshop.graph.recommendation.model.RecommendationExclusionReason.ALREADY_FOLLOWED
import io.bluetape4k.workshop.graph.recommendation.model.RecommendationExclusionReason.ALREADY_PURCHASED
import io.bluetape4k.workshop.graph.recommendation.model.RecommendationExclusionReason.SELF
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
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [RecommendationService]용 추상 테스트 suite입니다.
 *
 * 구체 하위 클래스는 특정 그래프 backend가 뒷받침하는 [ops]와 [service]를 제공합니다.
 * 각 테스트는 깨끗한 그래프에서 실행됩니다. [cleanGraph]가 매 테스트 전에 그래프를 drop하고 다시 초기화합니다.
 *
 * ## Seed 토폴로지([seedRecommendation] 참고)
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
 * ## alice의 기대 결과
 * - recommendProducts: [headphones(3), keyboard(1), mouse(1)]
 * - recommendFollows:  [dave(1), eve(1)]
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractRecommendationTest {

    protected abstract val graphName: String
    protected abstract val ops: GraphOperations
    protected abstract val service: RecommendationService

    @BeforeEach
    fun cleanGraph() {
        // bluetape4k-graph 0.6.0 requires selecting the logical graph before dropping it.
        ops.createGraph(graphName)
        ops.dropGraph(graphName)
        service.initialize()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. 정점 변경 메서드
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `addUser creates User vertex with correct properties`() {
        val alice = service.addUser(USER_ALICE, "Alice")

        alice.label shouldBeEqualTo "User"
        alice.properties[UserLabel.userId.name] shouldBeEqualTo USER_ALICE
        alice.properties[UserLabel.name.name] shouldBeEqualTo "Alice"
    }

    @Test
    fun `addUser returns existing vertex on second call (idempotent)`() {
        val first = service.addUser(USER_ALICE, "Alice")
        val second = service.addUser(USER_ALICE, "Alice")

        first.id shouldBeEqualTo second.id
    }

    @Test
    fun `addProduct creates Product vertex with correct properties`() {
        val laptop = service.addProduct("laptop", "Laptop", category = "Electronics")

        laptop.label shouldBeEqualTo "Product"
        laptop.properties["productId"] shouldBeEqualTo "laptop"
        laptop.properties["name"] shouldBeEqualTo "Laptop"
        laptop.properties["category"] shouldBeEqualTo "Electronics"
    }

    @Test
    fun `addProduct returns existing vertex on second call (idempotent)`() {
        val first = service.addProduct("laptop", "Laptop")
        val second = service.addProduct("laptop", "Laptop")

        first.id shouldBeEqualTo second.id
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. 입력 검증
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `addUser with blank userId throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.addUser("", "Alice")
        }
    }

    @Test
    fun `addUser with blank name throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.addUser(USER_ALICE, "")
        }
    }

    @Test
    fun `addProduct with blank productId throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            service.addProduct("", "Laptop")
        }
    }

    @Test
    fun `purchase with rating below 0 throws IllegalArgumentException`() {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")

        assertFailsWith<IllegalArgumentException> {
            service.purchase(alice.id, laptop.id, rating = -1)
        }
    }

    @Test
    fun `purchase with rating above 5 throws IllegalArgumentException`() {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")

        assertFailsWith<IllegalArgumentException> {
            service.purchase(alice.id, laptop.id, rating = 6)
        }
    }

    @Test
    fun `recommendProducts with limit 0 throws IllegalArgumentException`() {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.recommendProducts(alice.id, limit = 0)
        }
    }

    @Test
    fun `recommendProducts with limit above MAX throws IllegalArgumentException`() {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.recommendProducts(alice.id, limit = MAX_RECOMMENDATION_LIMIT + 1)
        }
    }

    @Test
    fun `recommendFollows with limit 0 throws IllegalArgumentException`() {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.recommendFollows(alice.id, limit = 0)
        }
    }

    @Test
    fun `recommendFollows with limit above MAX throws IllegalArgumentException`() {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.recommendFollows(alice.id, limit = MAX_RECOMMENDATION_LIMIT + 1)
        }
    }

    @Test
    fun `follow with same follower and followee throws IllegalArgumentException`() {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.follow(alice.id, alice.id)
        }
    }

    @Test
    fun `service rejects blank graphName`() {
        assertFailsWith<IllegalArgumentException> {
            RecommendationService(ops, "")
        }
    }

    @Test
    fun `purchase rejects non-product target endpoint`() {
        val alice = service.addUser(USER_ALICE, "Alice")
        val bob = service.addUser(USER_BOB, "Bob")

        assertFailsWith<IllegalArgumentException> {
            service.purchase(alice.id, bob.id, rating = 5)
        }
    }

    @Test
    fun `purchase rejects missing product endpoint`() {
        val alice = service.addUser(USER_ALICE, "Alice")

        assertFailsWith<IllegalArgumentException> {
            service.purchase(alice.id, GraphElementId.of("99999999"), rating = 5)
        }
    }

    @Test
    fun `follow rejects non-user target endpoint`() {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")

        assertFailsWith<IllegalArgumentException> {
            service.follow(alice.id, laptop.id)
        }
    }

    @Test
    fun `ProductRecommendation rejects score and sharedBuyers mismatch`() {
        val seed = seedRecommendation(service)

        assertFailsWith<IllegalArgumentException> {
            ProductRecommendation(seed.headphones, score = 2, sharedBuyers = listOf(seed.bob))
        }
    }

    @Test
    fun `FollowRecommendation rejects count and mutualFollows mismatch`() {
        val seed = seedRecommendation(service)

        assertFailsWith<IllegalArgumentException> {
            FollowRecommendation(seed.dave, mutualFollowCount = 2, mutualFollows = listOf(seed.bob))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. 간선 변경 메서드
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `purchase creates PURCHASED edge without rating`() {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")
        val edge = service.purchase(alice.id, laptop.id)

        edge.label shouldBeEqualTo PurchasedLabel.label
        edge.startId shouldBeEqualTo alice.id
        edge.endId shouldBeEqualTo laptop.id
    }

    @Test
    fun `purchase creates PURCHASED edge with rating stored as String`() {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")
        val edge = service.purchase(alice.id, laptop.id, rating = 5)

        edge.properties[PurchasedLabel.rating.name] shouldBeEqualTo "5"
    }

    @Test
    fun `purchase creates PURCHASED edge with purchasedAt stored`() {
        val alice = service.addUser(USER_ALICE, "Alice")
        val laptop = service.addProduct("laptop", "Laptop")
        val edge = service.purchase(alice.id, laptop.id, purchasedAt = "2024-01-15")

        edge.properties[PurchasedLabel.purchasedAt.name] shouldBeEqualTo "2024-01-15"
    }

    @Test
    fun `follow creates FOLLOWS edge between two users`() {
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
    fun `recommendProducts returns headphones with score 3`() {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id)

        results.shouldNotBeEmpty()
        val headphones = results.first { it.product.properties["productId"] == PROD_HEADPHONES }
        headphones.score shouldBeEqualTo 3
    }

    @Test
    fun `recommendProducts top result is headphones`() {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id)

        results.first().product.properties["productId"] shouldBeEqualTo PROD_HEADPHONES
    }

    @Test
    fun `recommendProducts returns correct descending score ordering`() {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id)
        val productIds = results.map { it.product.properties["productId"]?.toString() }

        // headphones(3) > keyboard(1) = mouse(1); tie-break는 사전순으로 keyboard < mouse입니다.
        productIds shouldBeEqualTo listOf(PROD_HEADPHONES, PROD_KEYBOARD, PROD_MOUSE)
    }

    @Test
    fun `recommendProducts alice own products not in results`() {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id)
        val productIds = results.map { it.product.id }

        productIds shouldNotContain seed.laptop.id
        productIds shouldNotContain seed.phone.id
        productIds shouldNotContain seed.tablet.id
    }

    @Test
    fun `recommendProducts user with no purchases returns empty`() {
        val isolated = service.addUser("isolated", "Isolated User")

        val results = service.recommendProducts(isolated.id)

        results.shouldBeEmpty()
    }

    @Test
    fun `recommendProducts limit 1 returns only top result`() {
        val seed = seedRecommendation(service)

        val results = service.recommendProducts(seed.alice.id, limit = 1)

        results shouldHaveSize 1
        results.first().product.properties["productId"] shouldBeEqualTo PROD_HEADPHONES
    }

    @Test
    fun `explainProductRecommendations returns co-buyer evidence and exclusions`() {
        val seed = seedRecommendation(service)

        val results = service.explainProductRecommendations(seed.alice.id)

        results.map { it.recommendation.product.properties["productId"]?.toString() } shouldBeEqualTo
                listOf(PROD_HEADPHONES, PROD_KEYBOARD, PROD_MOUSE)

        val headphones = results.first { it.recommendation.product.id == seed.headphones.id }
        headphones.recommendation.score shouldBeEqualTo 3
        headphones.evidencePaths shouldHaveSize 3
        headphones.evidencePaths.map { it.sharedProduct.id }.toSet() shouldBeEqualTo
                setOf(seed.laptop.id, seed.phone.id, seed.tablet.id)
        headphones.evidencePaths.map { it.coBuyer.id }.toSet() shouldBeEqualTo
                setOf(seed.bob.id, seed.carol.id, seed.dave.id)
        headphones.evidencePaths.map { it.candidateProduct.id }.toSet() shouldBeEqualTo
                setOf(seed.headphones.id)

        val exclusions = headphones.excludedCandidates.associate { it.candidateId to it.reason }
        exclusions.keys shouldContainAll listOf(seed.laptop.id, seed.phone.id, seed.tablet.id)
        exclusions[seed.laptop.id] shouldBeEqualTo ALREADY_PURCHASED
        exclusions[seed.phone.id] shouldBeEqualTo ALREADY_PURCHASED
        exclusions[seed.tablet.id] shouldBeEqualTo ALREADY_PURCHASED
    }

    @Test
    fun `explainProductRecommendations limit 1 returns only top explanation`() {
        val seed = seedRecommendation(service)

        val results = service.explainProductRecommendations(seed.alice.id, limit = 1)

        results shouldHaveSize 1
        results.first().recommendation.product.properties["productId"] shouldBeEqualTo PROD_HEADPHONES
        results.first().evidencePaths shouldHaveSize 3
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. recommendFollows
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `recommendFollows returns dave and eve for alice`() {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val userIds = results.map { it.person.properties[UserLabel.userId.name] as? String }

        userIds shouldContain USER_DAVE
        userIds shouldContain USER_EVE
    }

    @Test
    fun `recommendFollows result is sorted by mutualFollowCount desc then userId asc`() {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val userIds = results.map { it.person.properties[UserLabel.userId.name] as? String }

        // dave(1) = eve(1); tie-break는 사전순으로 dave < eve입니다.
        userIds shouldBeEqualTo listOf(USER_DAVE, USER_EVE)
    }

    @Test
    fun `recommendFollows alice herself not in results`() {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val personIds = results.map { it.person.id }

        personIds shouldNotContain seed.alice.id
    }

    @Test
    fun `recommendFollows already-followed users not in results`() {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val personIds = results.map { it.person.id }

        // alice는 bob과 carol을 follow하므로 추천에 나타나면 안 됩니다.
        personIds shouldNotContain seed.bob.id
        personIds shouldNotContain seed.carol.id
    }

    @Test
    fun `recommendFollows user with no follows returns empty`() {
        val isolated = service.addUser("isolated", "Isolated User")

        val results = service.recommendFollows(isolated.id)

        results.shouldBeEmpty()
    }

    @Test
    fun `recommendFollows limit 1 returns only top result`() {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id, limit = 1)

        results shouldHaveSize 1
        results.first().person.properties[UserLabel.userId.name] shouldBeEqualTo USER_DAVE
    }

    @Test
    fun `recommendFollows mutual follow count is correct`() {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)

        results shouldHaveSize 2
        results.forEach { rec ->
            rec.mutualFollowCount shouldBeEqualTo 1
        }
    }

    @Test
    fun `recommendFollows dave intermediary is bob`() {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val daveRec = results.first { it.person.properties[UserLabel.userId.name] == USER_DAVE }

        daveRec.mutualFollows.map { it.id } shouldContain seed.bob.id
    }

    @Test
    fun `recommendFollows eve intermediary is carol`() {
        val seed = seedRecommendation(service)

        val results = service.recommendFollows(seed.alice.id)
        val eveRec = results.first { it.person.properties[UserLabel.userId.name] == USER_EVE }

        eveRec.mutualFollows.map { it.id } shouldContain seed.carol.id
    }

    @Test
    fun `explainFollowRecommendations returns intermediary evidence and direct-follow exclusions`() {
        val seed = seedRecommendation(service)

        val results = service.explainFollowRecommendations(seed.alice.id)
        val userIds = results.map { it.recommendation.person.properties[UserLabel.userId.name] as? String }

        userIds shouldBeEqualTo listOf(USER_DAVE, USER_EVE)

        val dave = results.first { it.recommendation.person.id == seed.dave.id }
        dave.recommendation.mutualFollowCount shouldBeEqualTo 1
        dave.evidencePaths.map { it.intermediary.id } shouldContain seed.bob.id
        dave.evidencePaths.map { it.candidate.id }.toSet() shouldBeEqualTo setOf(seed.dave.id)

        val eve = results.first { it.recommendation.person.id == seed.eve.id }
        eve.recommendation.mutualFollowCount shouldBeEqualTo 1
        eve.evidencePaths.map { it.intermediary.id } shouldContain seed.carol.id
        eve.evidencePaths.map { it.candidate.id }.toSet() shouldBeEqualTo setOf(seed.eve.id)

        val exclusions = dave.excludedCandidates.associate { it.candidateId to it.reason }
        exclusions.keys shouldContainAll listOf(seed.bob.id, seed.carol.id)
        exclusions[seed.bob.id] shouldBeEqualTo ALREADY_FOLLOWED
        exclusions[seed.carol.id] shouldBeEqualTo ALREADY_FOLLOWED
    }

    @Test
    fun `explainFollowRecommendations records self exclusion when depth two traversal returns seed user`() {
        val seed = seedRecommendation(service)
        service.follow(seed.bob.id, seed.alice.id)

        val results = service.explainFollowRecommendations(seed.alice.id)
        val resultIds = results.map { it.recommendation.person.id }

        resultIds shouldNotContain seed.alice.id
        val selfExclusions = results.flatMap { it.excludedCandidates }
            .filter { it.candidateId == seed.alice.id }
        selfExclusions.shouldNotBeEmpty()
        selfExclusions.map { it.reason }.toSet() shouldBeEqualTo setOf(SELF)
        selfExclusions.map { it.via?.id }.toSet() shouldBeEqualTo setOf(seed.bob.id)
    }
}
