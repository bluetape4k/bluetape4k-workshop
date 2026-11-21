package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEmpty
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.assertions.shouldNotBeBlank
import io.bluetape4k.assertions.shouldNotBeEqualTo
import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.debug
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import java.nio.file.Path
import java.util.*
import kotlin.time.Duration.Companion.seconds

@Tag("integration")
/**
 * JobConsoleProxiedTopologyTest 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class JobConsoleProxiedTopologyTest {

    companion object: KLogging()

    @field:TempDir
/**
 * tempDir은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    lateinit var tempDir: Path

    @Test
/**
 * 이 시나리오는 "Docker resource snapshots the parent issued label set" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `Docker resource snapshots the parent issued label set`() {
        val labels = resources().redis.labels.toMutableMap()
        val resource = HighContentionDockerResource(
            resourceKey = labels.getValue(HighContentionDockerResource.RESOURCE_KEY_LABEL),
            resourceType = labels.getValue(HighContentionDockerResource.RESOURCE_TYPE_LABEL),
            labels = labels,
        )

        labels[HighContentionDockerResource.RUN_ID_LABEL] = "mutated"

        log.debug { "resource=$resource" }
        resource.labels.getValue(HighContentionDockerResource.RUN_ID_LABEL) shouldNotBeEqualTo "mutated"
        resource.journalFields.getValue("label.${HighContentionDockerResource.RUN_ID_LABEL}") shouldNotBeEqualTo "mutated"
    }

    @Test
/**
 * 이 시나리오는 "existing and new Redis connections fail on the real proxy path and recover
 * independently" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `existing and new Redis connections fail on the real proxy path and recover independently`() {
        val resources = resources()
        HighContentionJournal.open(tempDir, Path.of("redis-path.jsonl")).use { journal ->
            JobConsoleContainerFixture.proxiedRedis(journal, resources).use { topology ->
                topology.redisUri.contains("redis-primary").shouldBeFalse()
                topology.routesThroughProxy().shouldBeTrue()

                topology.openRedisConnection().use { existing ->
                    existing.ping() shouldBeEqualTo "PONG"

                    topology.cutExistingConnections()
                    await atMost 5.seconds untilAsserted {
                        assertFailsWith<Exception> { existing.ping() }
                    }
                    topology.restoreExistingConnections()
                }
                topology.openRedisConnection().use { recovered ->
                    recovered.ping() shouldBeEqualTo "PONG"
                }

                topology.disableNewConnections()
                await atMost 5.seconds untilAsserted {
                    assertFailsWith<Exception> {
                        topology.openRedisConnection().use(JobConsoleRedisConnection::ping)
                    }
                }
                topology.enableNewConnections()
                topology.openRedisConnection().use { recovered ->
                    await atMost 10.seconds untilAsserted {
                        recovered.ping() shouldBeEqualTo "PONG"
                    }
                }
            }
        }

        assertNoDockerObjects(resources)
    }

    @Test
/**
 * 이 시나리오는 "create intents and returned ids are journaled in strict order" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `create intents and returned ids are journaled in strict order`() {
        val resources = resources()
        val journalPath = tempDir.resolve("create-order.jsonl")

        HighContentionJournal.open(tempDir, journalPath.fileName).use { journal ->
            val topology = JobConsoleContainerFixture.proxiedRedis(journal, resources)
            try {
                topology.openRedisConnection().use { it.ping() shouldBeEqualTo "PONG" }
                assertDockerObjectsCarryLabels(HighContentionJournal.read(journalPath), resources)
            } finally {
                topology.close()
                topology.close()
            }
        }

        val records = HighContentionJournal.read(journalPath)
        resources.all.forEach { resource ->
            val resourceRecords = records.filter {
                it.payload.fields["resourceKey"] == resource.resourceKey
            }
            resourceRecords.map { it.payload.event } shouldBeEqualTo listOf(
                HighContentionJournalEvent.DOCKER_CREATE_INTENT,
                HighContentionJournalEvent.DOCKER_RESOURCE_CREATED,
            )
            resourceRecords.first().payload.fields shouldBeEqualTo resource.journalFields
            resourceRecords.last().payload.fields.getValue("dockerObjectId").shouldNotBeBlank()
        }
        assertNoDockerObjects(resources)
    }

/**
 * assertDockerObjectsCarryLabels은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun assertDockerObjectsCarryLabels(
        records: List<HighContentionJournalRecord>,
        resources: JobConsoleDockerResources,
    ) {
/**
 * docker은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
        val docker = DockerClientFactory.instance().client()
        resources.all.forEach { resource ->
/**
 * id은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val id = records.single {
                it.payload.event == HighContentionJournalEvent.DOCKER_RESOURCE_CREATED &&
                        it.payload.fields["resourceKey"] == resource.resourceKey
            }.payload.fields.getValue("dockerObjectId")
/**
 * actualLabels은 운영 작업 콘솔 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
            val actualLabels = if (resource.resourceType == "network") {
                docker.inspectNetworkCmd().withNetworkId(id).exec().labels
            } else {
                docker.inspectContainerCmd(id).exec().config.labels
            }.orEmpty()

            actualLabels.forEach { (key, value) -> log.debug { "$key: $value" } }
            resource.labels.forEach { (key, value) ->
                actualLabels.getValue(key) shouldBeEqualTo value
            }
        }
    }

    @Test
/**
 * 이 시나리오는 "failure after intent but before create leaves no Docker object and close stays
 * idempotent" 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `failure after intent but before create leaves no Docker object and close stays idempotent`() {
        val resources = resources()
        val journalPath = tempDir.resolve("before-create.jsonl")

        HighContentionJournal.open(tempDir, journalPath.fileName).use { journal ->
            assertFailsWith<ExpectedCutpointException> {
                JobConsoleProxiedTopology.start(
                    journal = journal,
                    resources = resources,
                    cutpoint = JobConsoleTopologyCutpoint.beforeCreate(resources.redis.resourceKey),
                )
            }
        }

        val records = HighContentionJournal.read(journalPath)
            .onEach { log.debug { "record=$it" } }

        records.last().payload.event shouldBeEqualTo HighContentionJournalEvent.DOCKER_CREATE_INTENT
        records.last().payload.fields shouldBeEqualTo resources.redis.journalFields
        assertNoDockerObjects(resources)
    }

    @Test
/**
 * 이 시나리오는 "failure after create return but before id fsync cleans the partially started topology"
 * 업무 규칙을 검증합니다.
 *
 * 입력 경계에서 명령을 한 번만 적용하고, 저장소의 확정 상태·이벤트·외부 응답이 같은 불변식을 만족하는지 확인합니다.
 */
    fun `failure after create return but before id fsync cleans the partially started topology`() {
        val resources = resources()
        val journalPath = tempDir.resolve("after-create.jsonl")

        HighContentionJournal.open(tempDir, journalPath.fileName).use { journal ->
            assertFailsWith<ExpectedCutpointException> {
                JobConsoleProxiedTopology.start(
                    journal = journal,
                    resources = resources,
                    cutpoint = JobConsoleTopologyCutpoint.afterCreate(resources.redis.resourceKey),
                )
            }
        }

        val redisRecords = HighContentionJournal.read(journalPath)
            .filter {
                it.payload.fields["resourceKey"] == resources.redis.resourceKey
            }
            .onEach { log.debug { "record=$it" } }

        redisRecords.map { it.payload.event } shouldBeEqualTo listOf(HighContentionJournalEvent.DOCKER_CREATE_INTENT)
        assertNoDockerObjects(resources)
    }

/**
 * resources은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun resources(): JobConsoleDockerResources {
        val runId = UUID.randomUUID().toString()
        val profileId = "redis-path-outage"
        return JobConsoleDockerResources(
            network = resource(runId, profileId, "redis-path-network", "network"),
            redis = resource(runId, profileId, "redis-primary", "container"),
            toxiproxy = resource(runId, profileId, "redis-toxiproxy", "container"),
        )
    }

/**
 * resource은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun resource(
        runId: String,
        profileId: String,
        resourceKey: String,
        resourceType: String,
    ): HighContentionDockerResource =
        HighContentionDockerResource(
            resourceKey = resourceKey,
            resourceType = resourceType,
            labels = mapOf(
                HighContentionDockerResource.RUN_ID_LABEL to runId,
                HighContentionDockerResource.PROFILE_ID_LABEL to profileId,
                HighContentionDockerResource.RESOURCE_KEY_LABEL to resourceKey,
                HighContentionDockerResource.RESOURCE_TYPE_LABEL to resourceType,
            ),
        )

/**
 * assertNoDockerObjects은 운영 작업 콘솔 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun assertNoDockerObjects(resources: JobConsoleDockerResources) {
        val docker = DockerClientFactory.instance().client()
        resources.all.forEach { resource ->
            docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(resource.labels)
                .exec()
                .shouldBeEmpty()

            docker.listNetworksCmd()
                .withFilter("label", resource.labels.map { (key, value) -> "$key=$value" })
                .exec()
                .shouldBeEmpty()
        }
    }
}
