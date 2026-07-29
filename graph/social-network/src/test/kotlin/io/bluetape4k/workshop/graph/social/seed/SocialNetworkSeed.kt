package io.bluetape4k.workshop.graph.social.seed

import io.bluetape4k.graph.model.GraphVertex
import io.bluetape4k.workshop.graph.social.service.SocialNetworkService
import io.bluetape4k.workshop.graph.social.service.SocialNetworkSuspendService
import java.io.Serializable

/**
 * [seedSocialNetwork]가 생성한 모든 정점의 스냅샷입니다.
 *
 * 그래프 구조:
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
 * 모든 `KNOWS` 간선은 양방향으로 저장됩니다(A -> B **및** B -> A).
 *
 * 주요 순회 기대값:
 * - alice의 1st-degree connection: [bob]
 * - alice의 2nd-degree connection: [carol, dave]
 * - alice의 FOAF 추천: carol(mutual=[bob], count=1), dave(mutual=[bob], count=1)
 *   mutualCount 내림차순, personId 오름차순으로 정렬 -> [carol, dave]
 * - alice의 colleague: [bob](둘 다 acme에서 근무)
 * - findMutualConnections(alice, dave) -> [bob]
 * - findConnectionPath(alice, dave) -> alice -> bob -> dave(길이 2)
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
// 블로킹 helper(T7a)
// ─────────────────────────────────────────────────────────────────────────

/**
 * [service]를 사용해 결정적인 5명 Person 그래프로 social network를 채웁니다.
 *
 * 전체 그래프 토폴로지와 순회 기대값은 [SocialNetworkSeed]를 참고합니다.
 */
fun seedSocialNetwork(service: SocialNetworkService): SocialNetworkSeed {
    val alice = service.addPerson("alice", "Alice Smith", title = "Engineer", location = "Seoul")
    val bob = service.addPerson("bob", "Bob Jones", title = "Designer", location = "Seoul")
    val carol = service.addPerson("carol", "Carol Park", title = "Developer", location = "Busan")
    val dave = service.addPerson("dave", "Dave Kim", title = "Manager", location = "Jeju")
    val eve = service.addPerson("eve", "Eve Lee", title = "Analyst", location = "Seoul")

    val acme = service.addCompany("acme", "Acme Corp", industry = "Technology", location = "Seoul")
    val startup = service.addCompany("startup", "Startup Inc", industry = "Software", location = "Busan")

    // 양방향 KNOWS connection
    service.connect(alice.id, bob.id, since = "2024-01-01", strength = 8)
    service.connect(bob.id, carol.id, since = "2024-03-15", strength = 6)
    service.connect(bob.id, dave.id, since = "2024-06-01", strength = 7)
    service.connect(carol.id, dave.id, since = "2024-07-01", strength = 5)

    // 단방향 FOLLOWS(eve가 alice를 follow하고 KNOWS는 없음)
    service.follow(eve.id, alice.id)

    // Work experience
    service.addWorkExperience(alice.id, acme.id, role = "Engineer", startDate = "2022-03-01", isCurrent = true)
    service.addWorkExperience(bob.id, acme.id, role = "Designer", startDate = "2023-06-01", isCurrent = true)
    service.addWorkExperience(carol.id, startup.id, role = "Developer", startDate = "2023-01-15", isCurrent = true)

    return SocialNetworkSeed(alice, bob, carol, dave, eve, acme, startup)
}

// ─────────────────────────────────────────────────────────────────────────
// Coroutine helper(T7b)
// ─────────────────────────────────────────────────────────────────────────

/**
 * [seedSocialNetwork]의 suspend 변형입니다.
 */
suspend fun seedSocialNetwork(service: SocialNetworkSuspendService): SocialNetworkSeed {
    val alice = service.addPerson("alice", "Alice Smith", title = "Engineer", location = "Seoul")
    val bob = service.addPerson("bob", "Bob Jones", title = "Designer", location = "Seoul")
    val carol = service.addPerson("carol", "Carol Park", title = "Developer", location = "Busan")
    val dave = service.addPerson("dave", "Dave Kim", title = "Manager", location = "Jeju")
    val eve = service.addPerson("eve", "Eve Lee", title = "Analyst", location = "Seoul")

    val acme = service.addCompany("acme", "Acme Corp", industry = "Technology", location = "Seoul")
    val startup = service.addCompany("startup", "Startup Inc", industry = "Software", location = "Busan")

    // 양방향 KNOWS connection
    service.connect(alice.id, bob.id, since = "2024-01-01", strength = 8)
    service.connect(bob.id, carol.id, since = "2024-03-15", strength = 6)
    service.connect(bob.id, dave.id, since = "2024-06-01", strength = 7)
    service.connect(carol.id, dave.id, since = "2024-07-01", strength = 5)

    // 단방향 FOLLOWS(eve가 alice를 follow하고 KNOWS는 없음)
    service.follow(eve.id, alice.id)

    // Work experience
    service.addWorkExperience(alice.id, acme.id, role = "Engineer", startDate = "2022-03-01", isCurrent = true)
    service.addWorkExperience(bob.id, acme.id, role = "Designer", startDate = "2023-06-01", isCurrent = true)
    service.addWorkExperience(carol.id, startup.id, role = "Developer", startDate = "2023-01-15", isCurrent = true)

    return SocialNetworkSeed(alice, bob, carol, dave, eve, acme, startup)
}
