package io.bluetape4k.workshop.operations.jobconsole.highcontention

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeFalse
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.operations.jobconsole.fixture.JobConsoleContainerFixture
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.DockerClientFactory
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

@Tag("integration")
class JobConsoleProxiedTopologyTest {

    @field:TempDir
    lateinit var tempDir: Path

    @Test
    fun `Docker resource snapshots the parent issued label set`() {
        val labels = resources().redis.labels.toMutableMap()
        val resource = HighContentionDockerResource(
            resourceKey = labels.getValue(HighContentionDockerResource.RESOURCE_KEY_LABEL),
            resourceType = labels.getValue(HighContentionDockerResource.RESOURCE_TYPE_LABEL),
            labels = labels,
        )

        labels[HighContentionDockerResource.RUN_ID_LABEL] = "mutated"

        (resource.labels.getValue(HighContentionDockerResource.RUN_ID_LABEL) == "mutated")
            .shouldBeFalse()
        (
            resource.journalFields.getValue("label.${HighContentionDockerResource.RUN_ID_LABEL}") ==
                "mutated"
        ).shouldBeFalse()
    }

    @Test
    fun `existing and new Redis connections fail on the real proxy path and recover independently`() {
        val resources = resources()
        HighContentionJournal.open(tempDir, Path.of("redis-path.jsonl")).use { journal ->
            JobConsoleContainerFixture.proxiedRedis(journal, resources).use { topology ->
                topology.redisUri.contains("redis-primary").shouldBeFalse()
                topology.routesThroughProxy().shouldBeTrue()

                topology.openRedisConnection().use { existing ->
                    existing.ping() shouldBeEqualTo "PONG"

                    topology.cutExistingConnections()
                    await.atMost(Duration.ofSeconds(5)).untilAsserted {
                        assertFailsWith<Exception> { existing.ping() }
                    }
                    topology.restoreExistingConnections()
                }
                topology.openRedisConnection().use { recovered ->
                    recovered.ping() shouldBeEqualTo "PONG"
                }

                topology.disableNewConnections()
                await.atMost(Duration.ofSeconds(5)).untilAsserted {
                    assertFailsWith<Exception> {
                        topology.openRedisConnection().use(JobConsoleRedisConnection::ping)
                    }
                }
                topology.enableNewConnections()
                topology.openRedisConnection().use { recovered ->
                    await.atMost(Duration.ofSeconds(10)).untilAsserted {
                        recovered.ping() shouldBeEqualTo "PONG"
                    }
                }
            }
        }

        assertNoDockerObjects(resources)
    }

    @Test
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
            resourceRecords.last().payload.fields.getValue("dockerObjectId").isNotBlank().shouldBeTrue()
        }
        assertNoDockerObjects(resources)
    }

    private fun assertDockerObjectsCarryLabels(
        records: List<HighContentionJournalRecord>,
        resources: JobConsoleDockerResources,
    ) {
        val docker = DockerClientFactory.instance().client()
        resources.all.forEach { resource ->
            val id = records.single {
                it.payload.event == HighContentionJournalEvent.DOCKER_RESOURCE_CREATED &&
                    it.payload.fields["resourceKey"] == resource.resourceKey
            }.payload.fields.getValue("dockerObjectId")
            val actualLabels = if (resource.resourceType == "network") {
                docker.inspectNetworkCmd().withNetworkId(id).exec().labels
            } else {
                docker.inspectContainerCmd(id).exec().config.labels
            }.orEmpty()
            resource.labels.forEach { (key, value) ->
                actualLabels.getValue(key) shouldBeEqualTo value
            }
        }
    }

    @Test
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
        records.last().payload.event shouldBeEqualTo HighContentionJournalEvent.DOCKER_CREATE_INTENT
        records.last().payload.fields shouldBeEqualTo resources.redis.journalFields
        assertNoDockerObjects(resources)
    }

    @Test
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

        val redisRecords = HighContentionJournal.read(journalPath).filter {
            it.payload.fields["resourceKey"] == resources.redis.resourceKey
        }
        redisRecords.map { it.payload.event } shouldBeEqualTo
            listOf(HighContentionJournalEvent.DOCKER_CREATE_INTENT)
        assertNoDockerObjects(resources)
    }

    private fun resources(): JobConsoleDockerResources {
        val runId = UUID.randomUUID().toString()
        val profileId = "redis-path-outage"
        return JobConsoleDockerResources(
            network = resource(runId, profileId, "redis-path-network", "network"),
            redis = resource(runId, profileId, "redis-primary", "container"),
            toxiproxy = resource(runId, profileId, "redis-toxiproxy", "container"),
        )
    }

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

    private fun assertNoDockerObjects(resources: JobConsoleDockerResources) {
        val docker = DockerClientFactory.instance().client()
        resources.all.forEach { resource ->
            docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(resource.labels)
                .exec()
                .shouldBeEqualTo(emptyList())
            docker.listNetworksCmd()
                .withFilter("label", resource.labels.map { (key, value) -> "$key=$value" })
                .exec()
                .shouldBeEqualTo(emptyList())
        }
    }
}
