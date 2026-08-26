package io.bluetape4k.workshop.messaging.kafka.multibroker.failover

import io.bluetape4k.assertions.shouldBeEqualTo
import io.bluetape4k.assertions.shouldBeTrue
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryConformanceFixture
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryObservation
import io.bluetape4k.workshop.shared.messaging.KafkaRecoveryPath
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverAdmin
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverAssignmentBarrier
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverClusterFixture
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverCollector
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverClientFactory
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverDeadline
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverEvidence
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverEvidenceWriter
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverPhase
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverPartitionState
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverPerformance
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverPerformanceWriter
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverResourceScope
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverTopology
import io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverBrokerSummaryWriter
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 실제 3-broker KRaft failover evidence이며 method는 의도적으로 직렬 실행합니다. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KafkaMultiBrokerFailoverIntegrationTest {

    @Test
    @Order(1)
    @DisplayName("data-leader-failover")
    fun dataLeaderFailover() {
        val fixture = KafkaFailoverClusterFixture()
        val writer = KafkaFailoverEvidenceWriter(fixture.runId)
        val performanceWriter = KafkaFailoverPerformanceWriter(fixture.runId)
        val scenarioDeadline = moduleDeadline().child(KafkaFailoverDeadline.SCENARIO_TIMEOUT)
        var terminalWritten = false
        var primaryFailure: Throwable? = null
        var observedAdmin: KafkaFailoverAdmin? = null
        var observedCollector: KafkaFailoverCollector? = null
        var acknowledgmentCount = 0
        var scenarioStartedAt = System.nanoTime()
        try {
            scenarioStartedAt = System.nanoTime()
            fixture.start()
            writer.append(evidence(fixture, KafkaFailoverPhase.STARTUP, status = "PASS"))

            val clients = KafkaFailoverClientFactory(KafkaFailoverKafkaConfiguration(fixture.bootstrapServers))
            val scope = KafkaFailoverResourceScope()
            try {
                val adminClient = clients.admin()
                scope.registerAdmin(adminClient)
                val admin = KafkaFailoverAdmin(adminClient)
                observedAdmin = admin
                admin.createReferenceTopic(scenarioDeadline.child(45.seconds))
                val topicState = admin.topicState(deadline = scenarioDeadline.child(15.seconds))
                topicState.size shouldBeEqualTo KafkaFailoverTopology.PARTITION_COUNT
                topicState.forEach { state ->
                    state.replicas.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()
                    state.isr.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()
                }
                val topicConfig = admin.topicConfig(deadline = scenarioDeadline.child(15.seconds))
                topicConfig["min.insync.replicas"] shouldBeEqualTo KafkaFailoverTopology.MIN_INSYNC_REPLICAS.toString()
                topicConfig["unclean.leader.election.enable"] shouldBeEqualTo "false"
                val referenceTopicState = topicState.first { it.partition == KafkaFailoverTopology.PARTITION }
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.TOPIC_READY,
                        topicState = referenceTopicState,
                        retryCount = admin.retryCount,
                    ),
                )

                val consumer = clients.consumer()
                scope.registerConsumer(consumer)
                val barrier = KafkaFailoverAssignmentBarrier()
                val collector = KafkaFailoverCollector()
                observedCollector = collector
                scope.registerCollector("collector") { collector.stop(scenarioDeadline.child(KafkaFailoverDeadline.CLEANUP_TIMEOUT)) }
                collector.start(consumer, assignmentBarrier = barrier)
                val assignment = barrier.await(scenarioDeadline.child(45.seconds))
                assignment.partitions.isNotEmpty().shouldBeTrue()
                val offsetsState = admin.awaitInternalTopic("__consumer_offsets", scenarioDeadline.child(15.seconds))
                offsetsState.size shouldBeEqualTo KafkaFailoverTopology.PARTITION_COUNT
                offsetsState.forEach { state ->
                    state.replicas.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()
                    state.isr.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()
                }
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.ASSIGNMENT_READY,
                        topicState = referenceTopicState,
                        assignmentCount = assignment.partitions.size,
                        retryCount = admin.retryCount,
                    ),
                )

                val producer = clients.producer()
                scope.registerProducer(producer)
                val preFault = admin.partitionState(deadline = scenarioDeadline.child(15.seconds))
                val stoppedLeader = preFault.leader ?: error("partition leader unavailable")
                preFault.isr.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()

                val prefix = (0 until KafkaFailoverEvidence.PREFIX_EVENTS).map { index ->
                    KafkaFailoverEvent("data-prefix-$index", index.toLong(), "data-prefix")
                }
                val prefixFutures = prefix.map { producer.send(clients.newProducerRecord(it)) }
                acknowledgmentCount += clients.awaitBatch(prefixFutures, scenarioDeadline.child(45.seconds))
                collector.awaitApplied(prefix.size, scenarioDeadline.child(45.seconds))
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.PREFIX_ACKED,
                        topicState = preFault,
                        assignmentCount = assignment.partitions.size,
                        rawDeliveryCount = collector.stats().rawDeliveryCount,
                        appliedCount = collector.stats().appliedCount,
                        conflictCount = collector.stats().conflictCount,
                        retryCount = admin.retryCount,
                    ),
                )

                val recoveryStartedAt = System.nanoTime()
                fixture.stopBroker(stoppedLeader)
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.FAULT_INJECTED,
                        topicState = preFault,
                        assignmentCount = assignment.partitions.size,
                        rawDeliveryCount = collector.stats().rawDeliveryCount,
                        appliedCount = collector.stats().appliedCount,
                        retryCount = admin.retryCount,
                    ),
                )

                val recovered = awaitPartition(admin, scenarioDeadline.child(15.seconds)) {
                    it.leader != null && it.leader != stoppedLeader && it.leader in preFault.isr
                }
                val leaderRecoveryElapsed = Duration.ofNanos(System.nanoTime() - recoveryStartedAt)
                check(recovered.leader in preFault.isr && recovered.leader != stoppedLeader) {
                    "recovered leader must be a surviving pre-fault ISR member"
                }
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.RECOVERY,
                        topicState = recovered,
                        retryCount = admin.retryCount,
                    ),
                )

                val suffix = (0 until KafkaFailoverEvidence.DATA_SUFFIX_EVENTS).map { index ->
                    KafkaFailoverEvent("data-suffix-$index", (prefix.size + index).toLong(), "data-suffix")
                }
                val suffixFutures = suffix.map { producer.send(clients.newProducerRecord(it)) }
                acknowledgmentCount += clients.awaitBatch(suffixFutures, scenarioDeadline.child(45.seconds))
                val stats = collector.awaitApplied(prefix.size + suffix.size, scenarioDeadline.child(45.seconds))
                val expectedIds = KafkaFailoverEvidence.exactLogicalIds(
                    prefix.map(KafkaFailoverEvent::eventId),
                    suffix.map(KafkaFailoverEvent::eventId),
                )
                stats.appliedEventIds shouldBeEqualTo expectedIds
                stats.conflictCount shouldBeEqualTo 0
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.SUFFIX_ACKED,
                        topicState = recovered,
                        assignmentCount = collector.callbackCount,
                        rawDeliveryCount = stats.rawDeliveryCount,
                        appliedCount = stats.appliedCount,
                        conflictCount = stats.conflictCount,
                        retryCount = admin.retryCount,
                    ),
                )

                fixture.restartBroker(stoppedLeader)
                val replacement = admin.awaitPartition(
                    KafkaFailoverEvent.TOPIC,
                    KafkaFailoverTopology.PARTITION,
                    scenarioDeadline.child(30.seconds),
                ) { it.replicas.contains(stoppedLeader) }
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.REPLACEMENT_READY,
                        topicState = replacement,
                        retryCount = admin.retryCount,
                    ),
                )
                val restored = admin.awaitPartition(
                    KafkaFailoverEvent.TOPIC,
                    KafkaFailoverTopology.PARTITION,
                    scenarioDeadline.child(30.seconds),
                ) { it.isr.size == KafkaFailoverTopology.REPLICATION_FACTOR.toInt() }
                restored.isr.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.ISR_RESTORED,
                        topicState = restored,
                        retryCount = admin.retryCount,
                    ),
                )
                KafkaRecoveryConformanceFixture(Duration.ofSeconds(15)).assertBrokerLeaderFailover(
                    KafkaRecoveryObservation(
                        path = KafkaRecoveryPath.BROKER_LEADER_FAILOVER,
                        logicalEventIds = expectedIds,
                        deliveredEventIds = expectedIds.toList(),
                        appliedEventIds = stats.appliedEventIds,
                        conflictCount = stats.conflictCount,
                        recoveryElapsed = leaderRecoveryElapsed,
                        recovered = true,
                        transportInterrupted = false,
                        leaderChanged = recovered.leader != stoppedLeader,
                        coordinatorChanged = false,
                        replacementReady = replacement.replicas.contains(stoppedLeader),
                        isrRestored = restored.isr.size == KafkaFailoverTopology.REPLICATION_FACTOR.toInt(),
                    ),
                )
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.TERMINAL,
                        topicState = restored,
                        assignmentCount = collector.callbackCount,
                        rawDeliveryCount = stats.rawDeliveryCount,
                        appliedCount = stats.appliedCount,
                        conflictCount = stats.conflictCount,
                        retryCount = admin.retryCount,
                        status = "PASS",
                    ),
                )
                terminalWritten = true
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                runCatching { scope.close() }
                    .onFailure { failure ->
                        if (primaryFailure == null) throw failure else primaryFailure.addSuppressed(failure)
                    }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            if (!terminalWritten) {
                runCatching {
                    writer.append(
                        evidence(
                            fixture,
                            KafkaFailoverPhase.TERMINAL,
                            retryCount = observedAdmin?.retryCount ?: 0,
                            status = "FAIL",
                        ),
                    )
                }
                    .onFailure(error::addSuppressed)
            }
            throw error
        } finally {
            var reportFailure: Throwable? = null
            runCatching {
                KafkaFailoverBrokerSummaryWriter.write(fixture.runId, fixture.brokerSnapshots())
            }.onFailure { reportFailure = it }
            val cleanupStartedAt = System.nanoTime()
            runCatching { fixture.close() }
                .onFailure { failure ->
                    if (reportFailure == null) reportFailure = failure else reportFailure.addSuppressed(failure)
                }
            val collectorStats = observedCollector?.stats()
            runCatching {
                performanceWriter.append(
                    KafkaFailoverPerformance(
                        runId = fixture.runId,
                        scenario = "data-leader-failover",
                        phase = KafkaFailoverPhase.TERMINAL.wireName,
                        elapsedMs = Duration.ofNanos(System.nanoTime() - scenarioStartedAt).toMillis().coerceAtLeast(0L),
                        deadlineRemainingMs = (scenarioDeadline.remainingNanos() / 1_000_000L).coerceAtLeast(0L),
                        adminRoundTripCount = observedAdmin?.adminRoundTripCount ?: 0,
                        ackCount = acknowledgmentCount,
                        pollCount = observedCollector?.pollCount ?: 0,
                        retryCount = observedAdmin?.retryCount ?: 0,
                        cleanupMs = Duration.ofNanos(System.nanoTime() - cleanupStartedAt).toMillis().coerceAtLeast(0L),
                        maxBufferedRecords = collectorStats?.maxBufferedRecords ?: 0,
                        maxBufferedBytes = collectorStats?.maxBufferedBytes ?: 0L,
                    ),
                )
            }.onFailure { failure ->
                if (reportFailure == null) reportFailure = failure else reportFailure.addSuppressed(failure)
            }
            writer.close()
            performanceWriter.close()
            reportFailure?.let { failure ->
                if (primaryFailure == null) throw failure else primaryFailure.addSuppressed(failure)
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("group-coordinator-failover")
    fun groupCoordinatorFailover() {
        val fixture = KafkaFailoverClusterFixture()
        val writer = KafkaFailoverEvidenceWriter(fixture.runId)
        val performanceWriter = KafkaFailoverPerformanceWriter(fixture.runId)
        val scenarioDeadline = moduleDeadline().child(KafkaFailoverDeadline.SCENARIO_TIMEOUT)
        var terminalWritten = false
        var primaryFailure: Throwable? = null
        var observedAdmin: KafkaFailoverAdmin? = null
        var observedRecoveryAdmin: KafkaFailoverAdmin? = null
        var observedCollector: KafkaFailoverCollector? = null
        var acknowledgmentCount = 0
        var scenarioStartedAt = System.nanoTime()
        try {
            scenarioStartedAt = System.nanoTime()
            fixture.start()
            writer.append(evidence(fixture, KafkaFailoverPhase.STARTUP, scenario = "group-coordinator-failover"))
            val clients = KafkaFailoverClientFactory(KafkaFailoverKafkaConfiguration(fixture.bootstrapServers))
            val scope = KafkaFailoverResourceScope()
            try {
                val adminClient = clients.admin()
                scope.registerAdmin(adminClient)
                val admin = KafkaFailoverAdmin(adminClient)
                observedAdmin = admin
                admin.createReferenceTopic(scenarioDeadline.child(45.seconds))
                val topicState = admin.topicState(deadline = scenarioDeadline.child(15.seconds))
                topicState.size shouldBeEqualTo KafkaFailoverTopology.PARTITION_COUNT
                topicState.forEach { state ->
                    state.replicas.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()
                    state.isr.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()
                }
                val topicConfig = admin.topicConfig(deadline = scenarioDeadline.child(15.seconds))
                topicConfig["min.insync.replicas"] shouldBeEqualTo KafkaFailoverTopology.MIN_INSYNC_REPLICAS.toString()
                topicConfig["unclean.leader.election.enable"] shouldBeEqualTo "false"
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.TOPIC_READY,
                        scenario = "group-coordinator-failover",
                        topicState = topicState.first { it.partition == KafkaFailoverTopology.PARTITION },
                        retryCount = admin.retryCount,
                    ),
                )
                val consumer = clients.consumer()
                scope.registerConsumer(consumer)
                val barrier = KafkaFailoverAssignmentBarrier()
                val collector = KafkaFailoverCollector()
                observedCollector = collector
                scope.registerCollector("collector") { collector.stop(scenarioDeadline.child(KafkaFailoverDeadline.CLEANUP_TIMEOUT)) }
                collector.start(consumer, assignmentBarrier = barrier)
                barrier.await(scenarioDeadline.child(45.seconds))
                val offsetsState = admin.awaitInternalTopic("__consumer_offsets", scenarioDeadline.child(15.seconds))
                offsetsState.size shouldBeEqualTo KafkaFailoverTopology.PARTITION_COUNT
                offsetsState.forEach { state ->
                    state.replicas.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()
                    state.isr.size shouldBeEqualTo KafkaFailoverTopology.REPLICATION_FACTOR.toInt()
                }
                val group = admin.groupState(KafkaFailoverKafkaConfiguration.GROUP_ID, scenarioDeadline.child(15.seconds))
                val coordinator = group.coordinator ?: error("group coordinator unavailable")
                val selected = admin.topicState(deadline = scenarioDeadline.child(15.seconds))
                    .firstOrNull { it.leader != null && it.leader != coordinator }
                    ?: error("no partition with a data leader distinct from coordinator")
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.ASSIGNMENT_READY,
                        scenario = "group-coordinator-failover",
                        topicState = selected,
                        coordinator = coordinator,
                        assignmentCount = group.assignmentCount,
                        retryCount = admin.retryCount,
                    ),
                )

                val producer = clients.producer()
                scope.registerProducer(producer)
                val prefix = (0 until KafkaFailoverEvidence.PREFIX_EVENTS).map { index ->
                    KafkaFailoverEvent("coordinator-prefix-$index", index.toLong(), "coordinator-prefix")
                }
                val prefixFutures = prefix.map { producer.send(clients.newProducerRecord(it, selected.partition)) }
                acknowledgmentCount += clients.awaitBatch(
                    prefixFutures,
                    scenarioDeadline.child(45.seconds),
                    expectedPartition = selected.partition,
                )
                val prefixStats = collector.awaitApplied(prefix.size, scenarioDeadline.child(45.seconds))
                prefixStats.appliedEventIds shouldBeEqualTo prefix.map(KafkaFailoverEvent::eventId).toSet()
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.PREFIX_ACKED,
                        scenario = "group-coordinator-failover",
                        topicState = selected,
                        coordinator = coordinator,
                        assignmentCount = group.assignmentCount,
                        rawDeliveryCount = prefixStats.rawDeliveryCount,
                        appliedCount = prefixStats.appliedCount,
                        conflictCount = prefixStats.conflictCount,
                        retryCount = admin.retryCount,
                    ),
                )
                val beforeCallbacks = collector.callbackCount
                val recoveryStartedAt = System.nanoTime()
                fixture.stopBroker(coordinator)
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.FAULT_INJECTED,
                        scenario = "group-coordinator-failover",
                        topicState = selected,
                        coordinator = coordinator,
                        assignmentCount = beforeCallbacks,
                        retryCount = admin.retryCount,
                    ),
                )

                // fault 전 AdminClient가 중지된 coordinator를 metadata cache에
                // 보관할 수 있으므로 살아남은 loopback endpoint에서 observation path를
                // 다시 bootstrap합니다. producer와 consumer ownership은 유지합니다.
                val recoveryClients =
                    KafkaFailoverClientFactory(KafkaFailoverKafkaConfiguration(fixture.bootstrapServers))
                val recoveryAdminClient = recoveryClients.admin()
                scope.registerAdmin(recoveryAdminClient, name = "recovery-admin")
                val recoveryAdmin = KafkaFailoverAdmin(recoveryAdminClient)
                observedRecoveryAdmin = recoveryAdmin
                collector.requestRebalance()
                val recoveredGroup = awaitGroup(recoveryAdmin, scenarioDeadline.child(15.seconds), coordinator) {
                    it.coordinator != null && it.coordinator != coordinator && collector.callbackCount > beforeCallbacks
                }
                val coordinatorRecoveryElapsed = Duration.ofNanos(System.nanoTime() - recoveryStartedAt)
                val recoveredPartition = recoveryAdmin.partitionState(deadline = scenarioDeadline.child(15.seconds))
                recoveredPartition.leader shouldBeEqualTo selected.leader
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.RECOVERY,
                        scenario = "group-coordinator-failover",
                        topicState = recoveredPartition,
                        coordinator = recoveredGroup.coordinator,
                        assignmentCount = collector.callbackCount,
                        retryCount = admin.retryCount + recoveryAdmin.retryCount,
                    ),
                )

                val suffix = (0 until KafkaFailoverEvidence.COORDINATOR_SUFFIX_EVENTS).map { index ->
                    KafkaFailoverEvent("coordinator-suffix-$index", (prefix.size + index).toLong(), "coordinator-suffix")
                }
                val suffixFutures = suffix.map { producer.send(clients.newProducerRecord(it, selected.partition)) }
                acknowledgmentCount += clients.awaitBatch(
                    suffixFutures,
                    scenarioDeadline.child(45.seconds),
                    expectedPartition = selected.partition,
                )
                val stats = collector.awaitApplied(prefix.size + suffix.size, scenarioDeadline.child(45.seconds))
                val expectedIds = KafkaFailoverEvidence.exactLogicalIds(
                    prefix.map(KafkaFailoverEvent::eventId),
                    suffix.map(KafkaFailoverEvent::eventId),
                )
                stats.appliedEventIds shouldBeEqualTo expectedIds
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.SUFFIX_ACKED,
                        scenario = "group-coordinator-failover",
                        topicState = recoveredPartition,
                        coordinator = recoveredGroup.coordinator,
                        assignmentCount = collector.callbackCount,
                        rawDeliveryCount = stats.rawDeliveryCount,
                        appliedCount = stats.appliedCount,
                        conflictCount = stats.conflictCount,
                        retryCount = admin.retryCount + recoveryAdmin.retryCount,
                    ),
                )
                fixture.restartBroker(coordinator)
                val replacement = recoveryAdmin.awaitPartition(
                    KafkaFailoverEvent.TOPIC,
                    selected.partition,
                    scenarioDeadline.child(30.seconds),
                ) { it.replicas.contains(coordinator) }
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.REPLACEMENT_READY,
                        scenario = "group-coordinator-failover",
                        topicState = replacement,
                        coordinator = recoveredGroup.coordinator,
                        assignmentCount = collector.callbackCount,
                        rawDeliveryCount = stats.rawDeliveryCount,
                        appliedCount = stats.appliedCount,
                        conflictCount = stats.conflictCount,
                        retryCount = admin.retryCount + recoveryAdmin.retryCount,
                    ),
                )
                val restored = recoveryAdmin.awaitPartition(
                    KafkaFailoverEvent.TOPIC,
                    selected.partition,
                    scenarioDeadline.child(30.seconds),
                ) { it.isr.size == KafkaFailoverTopology.REPLICATION_FACTOR.toInt() }
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.ISR_RESTORED,
                        scenario = "group-coordinator-failover",
                        topicState = restored,
                        coordinator = recoveredGroup.coordinator,
                        assignmentCount = collector.callbackCount,
                        rawDeliveryCount = stats.rawDeliveryCount,
                        appliedCount = stats.appliedCount,
                        conflictCount = stats.conflictCount,
                        retryCount = admin.retryCount + recoveryAdmin.retryCount,
                    ),
                )
                KafkaRecoveryConformanceFixture(Duration.ofSeconds(15)).assertBrokerCoordinatorFailover(
                    KafkaRecoveryObservation(
                        path = KafkaRecoveryPath.BROKER_COORDINATOR_FAILOVER,
                        logicalEventIds = expectedIds,
                        deliveredEventIds = expectedIds.toList(),
                        appliedEventIds = stats.appliedEventIds,
                        conflictCount = stats.conflictCount,
                        recoveryElapsed = coordinatorRecoveryElapsed,
                        recovered = true,
                        transportInterrupted = false,
                        leaderChanged = recoveredPartition.leader != selected.leader,
                        coordinatorChanged = recoveredGroup.coordinator != coordinator,
                        replacementReady = replacement.replicas.contains(coordinator),
                        isrRestored = restored.isr.size == KafkaFailoverTopology.REPLICATION_FACTOR.toInt(),
                    ),
                )
                writer.append(
                    evidence(
                        fixture,
                        KafkaFailoverPhase.TERMINAL,
                        scenario = "group-coordinator-failover",
                        topicState = restored,
                        coordinator = recoveredGroup.coordinator,
                        assignmentCount = collector.callbackCount,
                        rawDeliveryCount = stats.rawDeliveryCount,
                        appliedCount = stats.appliedCount,
                        conflictCount = stats.conflictCount,
                        retryCount = admin.retryCount + recoveryAdmin.retryCount,
                        status = "PASS",
                    ),
                )
                terminalWritten = true
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                runCatching { scope.close() }
                    .onFailure { failure ->
                        if (primaryFailure == null) throw failure else primaryFailure.addSuppressed(failure)
                    }
            }
        } catch (error: Throwable) {
            primaryFailure = error
            if (!terminalWritten) {
                runCatching {
                    writer.append(
                        evidence(
                            fixture,
                            KafkaFailoverPhase.TERMINAL,
                            scenario = "group-coordinator-failover",
                            retryCount = (observedAdmin?.retryCount ?: 0) + (observedRecoveryAdmin?.retryCount ?: 0),
                            status = "FAIL",
                        ),
                    )
                }
                    .onFailure(error::addSuppressed)
            }
            throw error
        } finally {
            var reportFailure: Throwable? = null
            runCatching {
                KafkaFailoverBrokerSummaryWriter.write(fixture.runId, fixture.brokerSnapshots())
            }.onFailure { reportFailure = it }
            val cleanupStartedAt = System.nanoTime()
            runCatching { fixture.close() }
                .onFailure { failure ->
                    if (reportFailure == null) reportFailure = failure else reportFailure.addSuppressed(failure)
                }
            val collectorStats = observedCollector?.stats()
            runCatching {
                performanceWriter.append(
                    KafkaFailoverPerformance(
                        runId = fixture.runId,
                        scenario = "group-coordinator-failover",
                        phase = KafkaFailoverPhase.TERMINAL.wireName,
                        elapsedMs = Duration.ofNanos(System.nanoTime() - scenarioStartedAt).toMillis().coerceAtLeast(0L),
                        deadlineRemainingMs = (scenarioDeadline.remainingNanos() / 1_000_000L).coerceAtLeast(0L),
                        adminRoundTripCount = (observedAdmin?.adminRoundTripCount ?: 0) +
                            (observedRecoveryAdmin?.adminRoundTripCount ?: 0),
                        ackCount = acknowledgmentCount,
                        pollCount = observedCollector?.pollCount ?: 0,
                        retryCount = (observedAdmin?.retryCount ?: 0) + (observedRecoveryAdmin?.retryCount ?: 0),
                        cleanupMs = Duration.ofNanos(System.nanoTime() - cleanupStartedAt).toMillis().coerceAtLeast(0L),
                        maxBufferedRecords = collectorStats?.maxBufferedRecords ?: 0,
                        maxBufferedBytes = collectorStats?.maxBufferedBytes ?: 0L,
                    ),
                )
            }.onFailure { failure ->
                if (reportFailure == null) reportFailure = failure else reportFailure.addSuppressed(failure)
            }
            writer.close()
            performanceWriter.close()
            reportFailure?.let { failure ->
                if (primaryFailure == null) throw failure else primaryFailure.addSuppressed(failure)
            }
        }
    }

    private fun evidence(
        fixture: KafkaFailoverClusterFixture,
        phase: KafkaFailoverPhase,
        scenario: String = "data-leader-failover",
        topicState: KafkaFailoverPartitionState? = null,
        coordinator: Int? = null,
        assignmentCount: Int? = null,
        rawDeliveryCount: Int? = null,
        appliedCount: Int? = null,
        conflictCount: Int? = null,
        retryCount: Int? = 0,
        status: String = "OBSERVED",
    ): KafkaFailoverEvidence {
        return KafkaFailoverEvidence(
            runId = fixture.runId,
            scenario = scenario,
            phase = phase,
            image = "apache/kafka",
            imageDigest = "sha256:${KafkaFailoverClusterFixture.APPROVED_IMAGE_DIGEST}",
            topic = KafkaFailoverTopology.TOPIC,
            partition = topicState?.partition,
            nodeCount = KafkaFailoverTopology.NODE_IDS.size,
            leader = topicState?.leader,
            replicas = topicState?.replicas ?: emptyList(),
            isr = topicState?.isr ?: emptyList(),
            coordinator = coordinator,
            assignmentCount = assignmentCount,
            rawDeliveryCount = rawDeliveryCount,
            appliedCount = appliedCount,
            conflictCount = conflictCount,
            retryCount = retryCount,
            status = status,
        )
    }

    private fun awaitPartition(
        admin: KafkaFailoverAdmin,
        deadline: KafkaFailoverDeadline,
        predicate: (KafkaFailoverPartitionState) -> Boolean,
    ) = admin.awaitPartition(KafkaFailoverTopology.TOPIC, KafkaFailoverTopology.PARTITION, deadline, predicate)

    private fun awaitGroup(
        admin: KafkaFailoverAdmin,
        deadline: KafkaFailoverDeadline,
        previousCoordinator: Int,
        predicate: (io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverGroupState) -> Boolean,
    ): io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverGroupState {
        var last: io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture.KafkaFailoverGroupState? = null
        while (deadline.remainingNanos() > 0L) {
            // 중지된 coordinator 때문에 AdminClient request 하나가 전체 deadline까지
            // blocking될 수 있습니다. 각 observation을 제한해 survivor endpoint를
            // 문서화된 15초 recovery window 안에서 retry하고 stale request 하나에
            // 전체 window를 소모하지 않도록 합니다.
            val state = runCatching {
                admin.groupState(
                    KafkaFailoverKafkaConfiguration.GROUP_ID,
                    deadline.child(2.seconds),
                )
            }.getOrElse { error ->
                if (!error.isTransientRecoveryFailure()) throw error
                null
            }
            last = state
            if (state != null && predicate(state)) return state
            Thread.sleep(minOf(200L, (deadline.remainingNanos() / 1_000_000L).coerceAtLeast(1L)))
        }
        error("group coordinator did not recover from $previousCoordinator; last=$last")
    }

    private fun Throwable.isTransientRecoveryFailure(): Boolean =
        this is org.apache.kafka.common.errors.RetriableException ||
            this is org.apache.kafka.common.errors.DisconnectException ||
            this is org.apache.kafka.common.errors.TimeoutException ||
            this is java.util.concurrent.TimeoutException ||
            cause?.isTransientRecoveryFailure() == true

    private fun moduleDeadline(): KafkaFailoverDeadline =
        MODULE_DEADLINE ?: KafkaFailoverDeadline.fromNow(KafkaFailoverDeadline.MODULE_TIMEOUT).also { MODULE_DEADLINE = it }

    companion object {
        @Volatile
        private var MODULE_DEADLINE: KafkaFailoverDeadline? = null
    }
}
