package io.bluetape4k.workshop.graph.social

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldContain
import io.bluetape4k.assertions.shouldNotBeEmpty
import io.bluetape4k.assertions.shouldNotBeNull
import io.bluetape4k.assertions.shouldNotContain
import io.bluetape4k.graph.repository.GraphSuspendOperations
import io.bluetape4k.workshop.graph.social.model.ConnectionRecommendation
import io.bluetape4k.workshop.graph.social.schema.CompanyLabel
import io.bluetape4k.workshop.graph.social.schema.KnowsLabel
import io.bluetape4k.workshop.graph.social.schema.PersonLabel
import io.bluetape4k.workshop.graph.social.schema.WorksAtLabel
import io.bluetape4k.workshop.graph.social.seed.SocialNetworkSeed
import io.bluetape4k.workshop.graph.social.seed.seedSocialNetwork
import io.bluetape4k.workshop.graph.social.service.SocialNetworkSuspendService
import kotlinx.coroutines.flow.toList
import io.bluetape4k.junit5.coroutines.runSuspendIO
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [SocialNetworkSuspendService]용 추상 테스트 suite입니다.
 *
 * 구체 하위 클래스는 특정 그래프 backend가 뒷받침하는 [ops]와 [service]를 제공합니다.
 * 각 테스트는 깨끗한 그래프에서 실행됩니다. [cleanGraph]가 매 테스트 전에 그래프를 drop하고 다시 초기화합니다.
 *
 * ## Seed 토폴로지
 * ```
 * alice ──KNOWS──► bob
 * bob   ──KNOWS──► carol
 * bob   ──KNOWS──► dave
 * carol ──KNOWS──► dave
 * eve   ──FOLLOWS──► alice (no KNOWS)
 * alice/bob ──WORKS_AT──► acme
 * carol ──WORKS_AT──► startup
 * ```
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractSocialNetworkSuspendTest {

    protected abstract val graphName: String
    protected abstract val ops: GraphSuspendOperations
    protected abstract val service: SocialNetworkSuspendService

    @BeforeEach
    fun cleanGraph() = runSuspendIO {
        ops.dropGraph(graphName)
        service.initialize()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. 정점 변경 메서드
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addPerson creates Person vertex with correct properties`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith", title = "Engineer", location = "Seoul")

        alice.label shouldBeEqualTo PersonLabel.label
        alice.properties[PersonLabel.personId.name] shouldBeEqualTo "alice"
        alice.properties[PersonLabel.name.name] shouldBeEqualTo "Alice Smith"
        alice.properties[PersonLabel.title.name] shouldBeEqualTo "Engineer"
        alice.properties[PersonLabel.location.name] shouldBeEqualTo "Seoul"
    }

    @Test
    fun `addPerson returns existing vertex on second call (idempotent)`() = runSuspendIO {
        val first = service.addPerson("alice", "Alice Smith")
        val second = service.addPerson("alice", "Alice Smith")

        first.id shouldBeEqualTo second.id
    }

    @Test
    fun `addCompany creates Company vertex with correct properties`() = runSuspendIO {
        val acme = service.addCompany("acme", "Acme Corp", industry = "Technology", location = "Seoul")

        acme.label shouldBeEqualTo CompanyLabel.label
        acme.properties[CompanyLabel.companyId.name] shouldBeEqualTo "acme"
        acme.properties[CompanyLabel.name.name] shouldBeEqualTo "Acme Corp"
        acme.properties[CompanyLabel.industry.name] shouldBeEqualTo "Technology"
    }

    @Test
    fun `addCompany returns existing vertex on second call (idempotent)`() = runSuspendIO {
        val first = service.addCompany("acme", "Acme Corp")
        val second = service.addCompany("acme", "Acme Corp")

        first.id shouldBeEqualTo second.id
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. 입력 검증
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addPerson with blank personId throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.addPerson("", "Alice Smith")
        }
    }

    @Test
    fun `addCompany with blank companyId throws IllegalArgumentException`() = runSuspendIO {
        assertFailsWith<IllegalArgumentException> {
            service.addCompany("", "Acme Corp")
        }
    }

    @Test
    fun `addWorkExperience with blank role throws IllegalArgumentException`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val acme = service.addCompany("acme", "Acme Corp")

        assertFailsWith<IllegalArgumentException> {
            service.addWorkExperience(alice.id, acme.id, role = "")
        }
    }

    @Test
    fun `connect with strength below minimum throws IllegalArgumentException`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val bob = service.addPerson("bob", "Bob Jones")

        assertFailsWith<IllegalArgumentException> {
            service.connect(alice.id, bob.id, strength = 0)
        }
    }

    @Test
    fun `findAllConnectionPaths with maxDepth exceeding MAX_TRAVERSAL_DEPTH throws IllegalArgumentException`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val bob = service.addPerson("bob", "Bob Jones")

        assertFailsWith<IllegalArgumentException> {
            service.findAllConnectionPaths(alice.id, bob.id, maxDepth = SocialNetworkSuspendService.MAX_TRAVERSAL_DEPTH + 1)
                .toList()
        }
    }

    @Test
    fun `service rejects blank graphName`() {
        assertFailsWith<IllegalArgumentException> {
            SocialNetworkSuspendService(ops, "")
        }
    }

    @Test
    fun `connect rejects same person endpoints`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")

        assertFailsWith<IllegalArgumentException> {
            service.connect(alice.id, alice.id)
        }
    }

    @Test
    fun `connect rejects company endpoint`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val acme = service.addCompany("acme", "Acme Corp")

        assertFailsWith<IllegalArgumentException> {
            service.connect(alice.id, acme.id)
        }
    }

    @Test
    fun `follow rejects company endpoint`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val acme = service.addCompany("acme", "Acme Corp")

        assertFailsWith<IllegalArgumentException> {
            service.follow(alice.id, acme.id)
        }
    }

    @Test
    fun `addWorkExperience rejects person endpoint as company target`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val bob = service.addPerson("bob", "Bob Jones")

        assertFailsWith<IllegalArgumentException> {
            service.addWorkExperience(alice.id, bob.id, role = "Engineer")
        }
    }

    @Test
    fun `ConnectionRecommendation rejects count and mutualConnections mismatch`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        assertFailsWith<IllegalArgumentException> {
            ConnectionRecommendation(seed.carol, mutualConnectionCount = 2, mutualConnections = listOf(seed.bob))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. 간선 변경 메서드
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `connect creates bidirectional KNOWS edges`() = runSuspendIO {
        val seed: SocialNetworkSeed = seedSocialNetwork(service)

        val aliceNeighbors = service.getDirectConnections(seed.alice.id).toList()
        aliceNeighbors.map { it.id } shouldContain seed.bob.id

        val bobNeighbors = service.getDirectConnections(seed.bob.id).toList()
        bobNeighbors.map { it.id } shouldContain seed.alice.id
    }

    @Test
    fun `connect stores strength as String property`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val bob = service.addPerson("bob", "Bob Jones")
        service.connect(alice.id, bob.id, since = "2024-01-01", strength = 8)

        val edges = ops.findEdgesByStartId(alice.id, KnowsLabel.label).toList()

        edges.shouldNotBeEmpty()
        val edge = edges.first { it.endId == bob.id }
        edge.properties[KnowsLabel.strength.name] shouldBeEqualTo "8"
        edge.properties[KnowsLabel.since.name] shouldBeEqualTo "2024-01-01"
    }

    @Test
    fun `follow creates unidirectional FOLLOWS edge`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val aliceNeighbors = service.getDirectConnections(seed.alice.id).toList()
        aliceNeighbors.map { it.id } shouldNotContain seed.eve.id
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. addWorkExperience
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `addWorkExperience creates WORKS_AT edge with correct properties`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val acme = service.addCompany("acme", "Acme Corp")
        val edge = service.addWorkExperience(alice.id, acme.id, role = "Engineer", startDate = "2022-03-01", isCurrent = true)

        edge.properties[WorksAtLabel.role.name] shouldBeEqualTo "Engineer"
        edge.properties[WorksAtLabel.startDate.name] shouldBeEqualTo "2022-03-01"
        edge.properties[WorksAtLabel.isCurrent.name] shouldBeEqualTo "true"
    }

    @Test
    fun `addWorkExperience with isCurrent=false stores false as String`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val acme = service.addCompany("acme", "Acme Corp")
        val edge = service.addWorkExperience(alice.id, acme.id, role = "Intern", isCurrent = false)

        edge.properties[WorksAtLabel.isCurrent.name] shouldBeEqualTo "false"
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. getDirectConnections
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `getDirectConnections returns 1st-degree KNOWS connections`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val connections = service.getDirectConnections(seed.alice.id).toList()

        connections.map { it.id } shouldContain seed.bob.id
        connections.map { it.id } shouldNotContain seed.carol.id
        connections.map { it.id } shouldNotContain seed.dave.id
    }

    @Test
    fun `getDirectConnections result does NOT contain seed vertex`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val connections = service.getDirectConnections(seed.alice.id).toList()

        connections.map { it.id } shouldNotContain seed.alice.id
    }

    // ─────────────────────────────────────────────────────────────────────
    // 6. getConnectionsWithinDegree
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `getConnectionsWithinDegree maxDegree=2 returns up to 2nd-degree connections`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val connections = service.getConnectionsWithinDegree(seed.alice.id, maxDegree = 2).toList()
        val ids = connections.map { it.id }

        ids shouldContain seed.bob.id
        ids shouldContain seed.carol.id
        ids shouldContain seed.dave.id
    }

    @Test
    fun `getConnectionsWithinDegree excludes seed vertex`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val connections = service.getConnectionsWithinDegree(seed.alice.id, maxDegree = 2).toList()

        connections.map { it.id } shouldNotContain seed.alice.id
    }

    // ─────────────────────────────────────────────────────────────────────
    // 7. getNthDegreeConnections
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `getNthDegreeConnections degree=1 returns only direct connections`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val firstDegree = service.getNthDegreeConnections(seed.alice.id, degree = 1)
        val ids = firstDegree.map { it.id }

        ids shouldContain seed.bob.id
        ids shouldNotContain seed.carol.id
        ids shouldNotContain seed.dave.id
    }

    @Test
    fun `getNthDegreeConnections degree=2 does NOT contain 1st-degree connections`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val secondDegree = service.getNthDegreeConnections(seed.alice.id, degree = 2)
        val ids = secondDegree.map { it.id }

        ids shouldNotContain seed.bob.id
        ids shouldContain seed.carol.id
        ids shouldContain seed.dave.id
    }

    @Test
    fun `getNthDegreeConnections degree=2 does NOT contain seed vertex`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val secondDegree = service.getNthDegreeConnections(seed.alice.id, degree = 2)

        secondDegree.map { it.id } shouldNotContain seed.alice.id
    }

    // ─────────────────────────────────────────────────────────────────────
    // 8. recommendConnections(FOAF)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `recommendConnections returns FOAF candidates with correct mutualCount`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val recs = service.recommendConnections(seed.alice.id)

        recs.shouldNotBeEmpty()
        recs.forEach { rec ->
            rec.mutualConnectionCount shouldBeEqualTo 1
            rec.mutualConnections.map { it.id } shouldContain seed.bob.id
        }
    }

    @Test
    fun `recommendConnections result is sorted by mutualCount desc then personId asc`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val recs = service.recommendConnections(seed.alice.id)
        val personIds = recs.map { it.person.properties[PersonLabel.personId.name] as? String }

        personIds shouldBeEqualTo listOf("carol", "dave")
    }

    @Test
    fun `recommendConnections excludes existing direct connections`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val recs = service.recommendConnections(seed.alice.id)
        val recPersonIds = recs.map { it.person.id }

        recPersonIds shouldNotContain seed.bob.id
    }

    @Test
    fun `recommendConnections returns empty for person with no connections`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val recs = service.recommendConnections(seed.eve.id)

        recs.shouldBeEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 9. findColleagues
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findColleagues returns colleagues at the same company`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val colleagues = service.findColleagues(seed.alice.id).toList()

        colleagues.map { it.id } shouldContain seed.bob.id
        colleagues.map { it.id } shouldNotContain seed.carol.id
    }

    @Test
    fun `findColleagues excludes the seed person`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val colleagues = service.findColleagues(seed.alice.id).toList()

        colleagues.map { it.id } shouldNotContain seed.alice.id
    }

    // ─────────────────────────────────────────────────────────────────────
    // 10. findConnectionPath
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findConnectionPath returns path of length 1 for directly connected persons`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val path = service.findConnectionPath(seed.alice.id, seed.bob.id)

        // vertices.size는 vertex-only path와 vertex+edge path 모두에서 동작합니다(vertices.size = hops + 1).
        path.shouldNotBeNull().vertices.size shouldBeEqualTo 2
    }

    @Test
    fun `findConnectionPath returns 2-hop path via intermediary`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val path = service.findConnectionPath(seed.alice.id, seed.dave.id)

        path.shouldNotBeNull().vertices.size shouldBeEqualTo 3
    }

    @Test
    fun `findConnectionPath returns null when no path exists`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val isolated = service.addPerson("isolated", "Isolated Person")

        val path = service.findConnectionPath(alice.id, isolated.id)

        path shouldBeEqualTo null
    }

    // ─────────────────────────────────────────────────────────────────────
    // 11. findAllConnectionPaths
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findAllConnectionPaths returns all paths within maxDepth`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val paths = service.findAllConnectionPaths(seed.alice.id, seed.dave.id, maxDepth = 3).toList()

        paths.shouldNotBeEmpty()
    }

    @Test
    fun `findAllConnectionPaths returns empty list for disconnected persons`() = runSuspendIO {
        val alice = service.addPerson("alice", "Alice Smith")
        val isolated = service.addPerson("isolated", "Isolated Person")

        val paths = service.findAllConnectionPaths(alice.id, isolated.id).toList()

        paths.shouldBeEmpty()
    }

    // ─────────────────────────────────────────────────────────────────────
    // 12. findMutualConnections
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `findMutualConnections returns shared direct connections`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val mutual = service.findMutualConnections(seed.alice.id, seed.dave.id)

        mutual.map { it.id } shouldContain seed.bob.id
    }

    @Test
    fun `findMutualConnections returns empty for non-overlapping networks`() = runSuspendIO {
        val seed = seedSocialNetwork(service)

        val mutual = service.findMutualConnections(seed.alice.id, seed.eve.id)

        mutual.shouldBeEmpty()
    }
}
