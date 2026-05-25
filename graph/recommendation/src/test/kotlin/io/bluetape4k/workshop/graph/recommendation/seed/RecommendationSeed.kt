package io.bluetape4k.workshop.graph.recommendation.seed

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationService
import io.bluetape4k.workshop.graph.recommendation.service.RecommendationSuspendService
import java.io.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Domain-key constants
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
 * Snapshot of all vertices created by [seedRecommendation].
 *
 * ## Graph topology
 *
 * ### PURCHASED edges (13)
 * ```
 * alice   → laptop(5), phone(4), tablet(3)
 * bob     → laptop(4), headphones(5)
 * carol   → phone(5),  headphones(4)
 * dave    → tablet(4), headphones(3)
 * eve     → laptop(3), keyboard(5)
 * frank   → phone(3),  mouse(4)
 * ```
 *
 * ### FOLLOWS edges (12)
 * ```
 * alice → bob, carol
 * bob   → dave, carol
 * carol → eve, bob
 * dave  → frank, eve
 * eve   → frank, dave
 * frank → alice, bob
 * ```
 *
 * ## Expected algorithm results for alice
 *
 * ### recommendProducts(alice)
 * - headphones: score=3 (co-buyers: bob via laptop, carol via phone, dave via tablet)
 * - keyboard:   score=1 (co-buyer:  eve via laptop)
 * - mouse:      score=1 (co-buyer:  frank via phone)
 * - Sort: descending score, then productId asc → [headphones(3), keyboard(1), mouse(1)]
 *
 * ### recommendFollows(alice)
 * - alice follows: {bob, carol} (myFollowIds)
 * - depth-2 from alice: bob→dave, bob→carol(excluded-depth1), carol→eve, carol→bob(excluded-depth1)
 * - Candidates: dave, eve
 * - dave:  INCOMING follows ∩ myFollowIds = {bob} → mutualFollowCount=1
 * - eve:   INCOMING follows ∩ myFollowIds = {carol} → mutualFollowCount=1
 * - Sort: descending count, then userId asc → [dave(1), eve(1)]
 *
 * Note: The task spec text listed 18 PURCHASED edges in an alternative topology, but that
 * topology produces mouse(4)/headphones(3)/keyboard(3), inconsistent with the spec's stated
 * expected output headphones(3)/keyboard(1)/mouse(1). This 13-edge topology is the canonical
 * one that matches the stated expected output.
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
// Blocking helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Populates the recommendation graph with a deterministic 12-vertex, 25-edge topology
 * using the blocking [RecommendationService].
 *
 * See [RecommendationSeed] for full topology and expected algorithm results.
 */
fun seedRecommendation(service: RecommendationService): RecommendationSeed {
    // Users
    val alice = service.addUser(USER_ALICE, "Alice")
    val bob = service.addUser(USER_BOB, "Bob")
    val carol = service.addUser(USER_CAROL, "Carol")
    val dave = service.addUser(USER_DAVE, "Dave")
    val eve = service.addUser(USER_EVE, "Eve")
    val frank = service.addUser(USER_FRANK, "Frank")

    // Products
    val laptop = service.addProduct(PROD_LAPTOP, "Laptop", category = "Electronics")
    val phone = service.addProduct(PROD_PHONE, "Phone", category = "Electronics")
    val tablet = service.addProduct(PROD_TABLET, "Tablet", category = "Electronics")
    val headphones = service.addProduct(PROD_HEADPHONES, "Headphones", category = "Accessories")
    val keyboard = service.addProduct(PROD_KEYBOARD, "Keyboard", category = "Accessories")
    val mouse = service.addProduct(PROD_MOUSE, "Mouse", category = "Accessories")

    // PURCHASED edges (13)
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

    // FOLLOWS edges (12)
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
// Coroutine helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Suspend variant of [seedRecommendation].
 */
suspend fun seedRecommendation(service: RecommendationSuspendService): RecommendationSeed {
    // Users
    val alice = service.addUser(USER_ALICE, "Alice")
    val bob = service.addUser(USER_BOB, "Bob")
    val carol = service.addUser(USER_CAROL, "Carol")
    val dave = service.addUser(USER_DAVE, "Dave")
    val eve = service.addUser(USER_EVE, "Eve")
    val frank = service.addUser(USER_FRANK, "Frank")

    // Products
    val laptop = service.addProduct(PROD_LAPTOP, "Laptop", category = "Electronics")
    val phone = service.addProduct(PROD_PHONE, "Phone", category = "Electronics")
    val tablet = service.addProduct(PROD_TABLET, "Tablet", category = "Electronics")
    val headphones = service.addProduct(PROD_HEADPHONES, "Headphones", category = "Accessories")
    val keyboard = service.addProduct(PROD_KEYBOARD, "Keyboard", category = "Accessories")
    val mouse = service.addProduct(PROD_MOUSE, "Mouse", category = "Accessories")

    // PURCHASED edges (13)
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

    // FOLLOWS edges (12)
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
