package io.bluetape4k.workshop.graph.recommendation.seed

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationService
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationSuspendService
import java.io.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// 도메인 키 상수
// ─────────────────────────────────────────────────────────────────────────────

const val USER_ALICE = "alice"
const val USER_BOB = "bob"
const val USER_CAROL = "carol"
const val USER_DAVE = "dave"
const val USER_EVE = "eve"
const val USER_FRANK = "frank"

const val PROD_LAPTOP = "laptop"
const val PROD_PHONE = "phone"
const val PROD_TABLET = "tablet"
const val PROD_HEADPHONES = "headphones"
const val PROD_KEYBOARD = "keyboard"
const val PROD_MOUSE = "mouse"

/**
 * [seedRecommendation]이 생성한 모든 정점의 스냅샷입니다.
 *
 * ## 그래프 토폴로지
 *
 * ### PURCHASED 간선(13개)
 * ```
 * alice   → laptop(5), phone(4), tablet(3)
 * bob     → laptop(4), headphones(5)
 * carol   → phone(5),  headphones(4)
 * dave    → tablet(4), headphones(3)
 * eve     → laptop(3), keyboard(5)
 * frank   → phone(3),  mouse(4)
 * ```
 *
 * ### FOLLOWS 간선(12개)
 * ```
 * alice → bob, carol
 * bob   → dave, carol
 * carol → eve, bob
 * dave  → frank, eve
 * eve   → frank, dave
 * frank → alice, bob
 * ```
 *
 * ## alice에 대한 알고리즘 기대 결과
 *
 * ### recommendProducts(alice)
 * - headphones: score=3 (co-buyers: bob via laptop, carol via phone, dave via tablet)
 * - keyboard:   score=1 (co-buyer:  eve via laptop)
 * - mouse:      score=1 (co-buyer:  frank via phone)
 * - 정렬: score 내림차순, productId 오름차순 -> [headphones(3), keyboard(1), mouse(1)]
 *
 * ### recommendFollows(alice)
 * - alice follows: {bob, carol}(myFollowIds)
 * - alice에서 depth-2: bob -> dave, bob -> carol(excluded-depth1), carol -> eve, carol -> bob(excluded-depth1)
 * - 후보: dave, eve
 * - dave:  INCOMING follows와 myFollowIds의 교집합 = {bob} -> mutualFollowCount=1
 * - eve:   INCOMING follows와 myFollowIds의 교집합 = {carol} -> mutualFollowCount=1
 * - 정렬: count 내림차순, userId 오름차순 -> [dave(1), eve(1)]
 *
 * 참고: task spec 문구에는 대안 토폴로지로 `PURCHASED` 간선 18개가 적혀 있었지만,
 * 그 토폴로지는 mouse(4)/headphones(3)/keyboard(3)를 만들어 spec의 명시 기대값인
 * headphones(3)/keyboard(1)/mouse(1)와 맞지 않습니다. 이 13개 간선 토폴로지가
 * 명시 기대값과 일치하는 canonical 토폴로지입니다.
 */
data class RecommendationSeed(
    val alice: GraphVertex,
    val bob: GraphVertex,
    val carol: GraphVertex,
    val dave: GraphVertex,
    val eve: GraphVertex,
    val frank: GraphVertex,
    val laptop: GraphVertex,
    val phone: GraphVertex,
    val tablet: GraphVertex,
    val headphones: GraphVertex,
    val keyboard: GraphVertex,
    val mouse: GraphVertex,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 블로킹 helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 블로킹 [RecommendationService]를 사용해 결정적인 12개 정점, 25개 간선 토폴로지로
 * 추천 그래프를 채웁니다.
 *
 * 전체 토폴로지와 알고리즘 기대 결과는 [RecommendationSeed]를 참고합니다.
 */
fun seedRecommendation(service: RecommendationService): RecommendationSeed {
    // User 정점
    val alice = service.addUser(USER_ALICE, "Alice")
    val bob = service.addUser(USER_BOB, "Bob")
    val carol = service.addUser(USER_CAROL, "Carol")
    val dave = service.addUser(USER_DAVE, "Dave")
    val eve = service.addUser(USER_EVE, "Eve")
    val frank = service.addUser(USER_FRANK, "Frank")

    // Product 정점
    val laptop = service.addProduct(PROD_LAPTOP, "Laptop", category = "Electronics")
    val phone = service.addProduct(PROD_PHONE, "Phone", category = "Electronics")
    val tablet = service.addProduct(PROD_TABLET, "Tablet", category = "Electronics")
    val headphones = service.addProduct(PROD_HEADPHONES, "Headphones", category = "Accessories")
    val keyboard = service.addProduct(PROD_KEYBOARD, "Keyboard", category = "Accessories")
    val mouse = service.addProduct(PROD_MOUSE, "Mouse", category = "Accessories")

    // PURCHASED 간선(13개)
    service.purchase(alice.id, laptop.id, rating = 5)
    service.purchase(alice.id, phone.id, rating = 4)
    service.purchase(alice.id, tablet.id, rating = 3)

    service.purchase(bob.id, laptop.id, rating = 4)
    service.purchase(bob.id, headphones.id, rating = 5)

    service.purchase(carol.id, phone.id, rating = 5)
    service.purchase(carol.id, headphones.id, rating = 4)

    service.purchase(dave.id, tablet.id, rating = 4)
    service.purchase(dave.id, headphones.id, rating = 3)

    service.purchase(eve.id, laptop.id, rating = 3)
    service.purchase(eve.id, keyboard.id, rating = 5)

    service.purchase(frank.id, phone.id, rating = 3)
    service.purchase(frank.id, mouse.id, rating = 4)

    // FOLLOWS 간선(12개)
    service.follow(alice.id, bob.id)
    service.follow(alice.id, carol.id)
    service.follow(bob.id, dave.id)
    service.follow(bob.id, carol.id)
    service.follow(carol.id, eve.id)
    service.follow(carol.id, bob.id)
    service.follow(dave.id, frank.id)
    service.follow(dave.id, eve.id)
    service.follow(eve.id, frank.id)
    service.follow(eve.id, dave.id)
    service.follow(frank.id, alice.id)
    service.follow(frank.id, bob.id)

    return RecommendationSeed(alice, bob, carol, dave, eve, frank, laptop, phone, tablet, headphones, keyboard, mouse)
}

// ─────────────────────────────────────────────────────────────────────────────
// Coroutine helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * [seedRecommendation]의 suspend 변형입니다.
 */
suspend fun seedRecommendation(service: RecommendationSuspendService): RecommendationSeed {
    // User 정점
    val alice = service.addUser(USER_ALICE, "Alice")
    val bob = service.addUser(USER_BOB, "Bob")
    val carol = service.addUser(USER_CAROL, "Carol")
    val dave = service.addUser(USER_DAVE, "Dave")
    val eve = service.addUser(USER_EVE, "Eve")
    val frank = service.addUser(USER_FRANK, "Frank")

    // Product 정점
    val laptop = service.addProduct(PROD_LAPTOP, "Laptop", category = "Electronics")
    val phone = service.addProduct(PROD_PHONE, "Phone", category = "Electronics")
    val tablet = service.addProduct(PROD_TABLET, "Tablet", category = "Electronics")
    val headphones = service.addProduct(PROD_HEADPHONES, "Headphones", category = "Accessories")
    val keyboard = service.addProduct(PROD_KEYBOARD, "Keyboard", category = "Accessories")
    val mouse = service.addProduct(PROD_MOUSE, "Mouse", category = "Accessories")

    // PURCHASED 간선(13개)
    service.purchase(alice.id, laptop.id, rating = 5)
    service.purchase(alice.id, phone.id, rating = 4)
    service.purchase(alice.id, tablet.id, rating = 3)

    service.purchase(bob.id, laptop.id, rating = 4)
    service.purchase(bob.id, headphones.id, rating = 5)

    service.purchase(carol.id, phone.id, rating = 5)
    service.purchase(carol.id, headphones.id, rating = 4)

    service.purchase(dave.id, tablet.id, rating = 4)
    service.purchase(dave.id, headphones.id, rating = 3)

    service.purchase(eve.id, laptop.id, rating = 3)
    service.purchase(eve.id, keyboard.id, rating = 5)

    service.purchase(frank.id, phone.id, rating = 3)
    service.purchase(frank.id, mouse.id, rating = 4)

    // FOLLOWS 간선(12개)
    service.follow(alice.id, bob.id)
    service.follow(alice.id, carol.id)
    service.follow(bob.id, dave.id)
    service.follow(bob.id, carol.id)
    service.follow(carol.id, eve.id)
    service.follow(carol.id, bob.id)
    service.follow(dave.id, frank.id)
    service.follow(dave.id, eve.id)
    service.follow(eve.id, frank.id)
    service.follow(eve.id, dave.id)
    service.follow(frank.id, alice.id)
    service.follow(frank.id, bob.id)

    return RecommendationSeed(alice, bob, carol, dave, eve, frank, laptop, phone, tablet, headphones, keyboard, mouse)
}
