package io.bluetape4k.workshop.graph.social.seed

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.workshop.graph.social.service.SocialNetworkService
import io.bluetape4k.workshop.graph.social.service.SocialNetworkSuspendService
import java.io.Serializable

/**
 * Snapshot of all vertices created by [seedSocialNetwork].
 *
 * Graph structure:
 * ```
 * alice ──KNOWS──► bob   (strength=8, since=2024-01-01)
 * bob   ──KNOWS──► carol (strength=6, since=2024-03-15)
 * bob   ──KNOWS──► dave  (strength=7, since=2024-06-01)
 * carol ──KNOWS──► dave  (strength=5, since=2024-07-01)
 *
 * eve   ──FOLLOWS──► alice          (unidirectional; eve has no KNOWS edges)
 *
 * alice ──WORKS_AT──► acme    (role="Engineer",  isCurrent=true)
 * bob   ──WORKS_AT──► acme    (role="Designer",  isCurrent=true)
 * carol ──WORKS_AT──► startup (role="Developer", isCurrent=true)
 * ```
 *
 * All KNOWS edges are stored bidirectionally (A→B **and** B→A).
 *
 * Key traversal expectations:
 * - alice's 1st-degree connections: [bob]
 * - alice's 2nd-degree connections: [carol, dave]
 * - FOAF recommendations for alice: carol (mutual=[bob], count=1), dave (mutual=[bob], count=1)
 *   sorted by mutualCount desc then personId asc → [carol, dave]
 * - alice's colleagues: [bob]  (both work at acme)
 * - findMutualConnections(alice, dave) → [bob]
 * - findConnectionPath(alice, dave) → alice→bob→dave (length 2)
 */
data class SocialNetworkSeed(
    val alice: GraphVertex,
    val bob: GraphVertex,
    val carol: GraphVertex,
    val dave: GraphVertex,
    val eve: GraphVertex,
    val acme: GraphVertex,
    val startup: GraphVertex,
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Blocking helpers (T7a)
// ─────────────────────────────────────────────────────────────────────────

/**
 * Populates the social network with a deterministic five-person graph using [service].
 *
 * See [SocialNetworkSeed] for the full graph topology and traversal expectations.
 */
fun seedSocialNetwork(service: SocialNetworkService): SocialNetworkSeed {
    val alice = service.addPerson("alice", "Alice Smith", title = "Engineer", location = "Seoul")
    val bob = service.addPerson("bob", "Bob Jones", title = "Designer", location = "Seoul")
    val carol = service.addPerson("carol", "Carol Park", title = "Developer", location = "Busan")
    val dave = service.addPerson("dave", "Dave Kim", title = "Manager", location = "Jeju")
    val eve = service.addPerson("eve", "Eve Lee", title = "Analyst", location = "Seoul")

    val acme = service.addCompany("acme", "Acme Corp", industry = "Technology", location = "Seoul")
    val startup = service.addCompany("startup", "Startup Inc", industry = "Software", location = "Busan")

    // Bidirectional KNOWS connections
    service.connect(alice.id, bob.id, since = "2024-01-01", strength = 8)
    service.connect(bob.id, carol.id, since = "2024-03-15", strength = 6)
    service.connect(bob.id, dave.id, since = "2024-06-01", strength = 7)
    service.connect(carol.id, dave.id, since = "2024-07-01", strength = 5)

    // Unidirectional FOLLOWS (eve follows alice; no KNOWS)
    service.follow(eve.id, alice.id)

    // Work experience
    service.addWorkExperience(alice.id, acme.id, role = "Engineer", startDate = "2022-03-01", isCurrent = true)
    service.addWorkExperience(bob.id, acme.id, role = "Designer", startDate = "2023-06-01", isCurrent = true)
    service.addWorkExperience(carol.id, startup.id, role = "Developer", startDate = "2023-01-15", isCurrent = true)

    return SocialNetworkSeed(alice, bob, carol, dave, eve, acme, startup)
}

// ─────────────────────────────────────────────────────────────────────────
// Coroutine helpers (T7b)
// ─────────────────────────────────────────────────────────────────────────

/**
 * Suspend variant of [seedSocialNetwork].
 */
suspend fun seedSocialNetwork(service: SocialNetworkSuspendService): SocialNetworkSeed {
    val alice = service.addPerson("alice", "Alice Smith", title = "Engineer", location = "Seoul")
    val bob = service.addPerson("bob", "Bob Jones", title = "Designer", location = "Seoul")
    val carol = service.addPerson("carol", "Carol Park", title = "Developer", location = "Busan")
    val dave = service.addPerson("dave", "Dave Kim", title = "Manager", location = "Jeju")
    val eve = service.addPerson("eve", "Eve Lee", title = "Analyst", location = "Seoul")

    val acme = service.addCompany("acme", "Acme Corp", industry = "Technology", location = "Seoul")
    val startup = service.addCompany("startup", "Startup Inc", industry = "Software", location = "Busan")

    // Bidirectional KNOWS connections
    service.connect(alice.id, bob.id, since = "2024-01-01", strength = 8)
    service.connect(bob.id, carol.id, since = "2024-03-15", strength = 6)
    service.connect(bob.id, dave.id, since = "2024-06-01", strength = 7)
    service.connect(carol.id, dave.id, since = "2024-07-01", strength = 5)

    // Unidirectional FOLLOWS (eve follows alice; no KNOWS)
    service.follow(eve.id, alice.id)

    // Work experience
    service.addWorkExperience(alice.id, acme.id, role = "Engineer", startDate = "2022-03-01", isCurrent = true)
    service.addWorkExperience(bob.id, acme.id, role = "Designer", startDate = "2023-06-01", isCurrent = true)
    service.addWorkExperience(carol.id, startup.id, role = "Developer", startDate = "2023-01-15", isCurrent = true)

    return SocialNetworkSeed(alice, bob, carol, dave, eve, acme, startup)
}
