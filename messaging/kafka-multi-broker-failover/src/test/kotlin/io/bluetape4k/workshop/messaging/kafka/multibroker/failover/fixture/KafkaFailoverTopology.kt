package io.bluetape4k.workshop.messaging.kafka.multibroker.failover.fixture

import java.net.InetAddress
import java.net.URI

/**
 * 세 broker가 공유하는 KRaft 토폴로지와 listener 경계를 한 곳에서 관리합니다.
 * Testcontainers 기본 single-node 값을 사용하지 않고 모든 quorum 값을 명시합니다.
 */
object KafkaFailoverTopology {

    const val TOPIC: String = "kafka-failover-reference"
    const val PARTITION_COUNT: Int = 3
    const val REPLICATION_FACTOR: Short = 3
    const val MIN_INSYNC_REPLICAS: Short = 2
    const val PARTITION: Int = 0
    const val CLUSTER_ID: String = "4L6g3nShT-eMCtK--X86sw"
    const val KAFKA_LISTENERS: String =
        "PLAINTEXT://0.0.0.0:9092,BROKER://0.0.0.0:9093,CONTROLLER://0.0.0.0:9094"
    const val CONTROLLER_QUORUM_VOTERS: String =
        "1@kafka-1:9094,2@kafka-2:9094,3@kafka-3:9094"

    val NODE_IDS: List<Int> = listOf(1, 2, 3)

    fun brokerAlias(nodeId: Int): String {
        require(nodeId in NODE_IDS) { "unsupported Kafka broker node id: $nodeId" }
        return "kafka-$nodeId"
    }

    fun controllerQuorumVoters(): String = CONTROLLER_QUORUM_VOTERS

    fun brokerEnvironment(nodeId: Int): Map<String, String> {
        val alias = brokerAlias(nodeId)
        return linkedMapOf(
            "CLUSTER_ID" to CLUSTER_ID,
            "KAFKA_NODE_ID" to nodeId.toString(),
            "KAFKA_PROCESS_ROLES" to "broker,controller",
            "KAFKA_CONTROLLER_QUORUM_VOTERS" to CONTROLLER_QUORUM_VOTERS,
            "KAFKA_LISTENERS" to KAFKA_LISTENERS,
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP" to
                "PLAINTEXT:PLAINTEXT,BROKER:PLAINTEXT,CONTROLLER:PLAINTEXT",
            "KAFKA_INTER_BROKER_LISTENER_NAME" to "BROKER",
            "KAFKA_CONTROLLER_LISTENER_NAMES" to "CONTROLLER",
            "KAFKA_AUTO_CREATE_TOPICS_ENABLE" to "false",
            "KAFKA_UNCLEAN_LEADER_ELECTION_ENABLE" to "false",
            "KAFKA_OFFSETS_TOPIC_NUM_PARTITIONS" to PARTITION_COUNT.toString(),
            "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR" to REPLICATION_FACTOR.toString(),
            "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR" to REPLICATION_FACTOR.toString(),
            "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR" to MIN_INSYNC_REPLICAS.toString(),
            "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS" to "0",
            "KAFKA_BROKER_ID" to nodeId.toString(),
            "KAFKA_ADVERTISED_HOST_NAME" to alias,
        )
    }

    /** host JVM에 노출할 수 있는 endpoint인지 확인합니다. */
    fun isHostAdvertisedListener(listener: String): Boolean {
        return runCatching {
            val uri = URI(listener)
            val host = uri.host ?: return false
            uri.scheme.equals("PLAINTEXT", ignoreCase = true) &&
                uri.userInfo == null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.query == null &&
                uri.fragment == null &&
                uri.port in 1..65_535 &&
                resolveAll(host).all(InetAddress::isLoopbackAddress)
        }.getOrDefault(false)
    }

    private fun resolveAll(host: String): Array<InetAddress> =
        InetAddress.getAllByName(host).also { addresses ->
            require(addresses.isNotEmpty()) { "listener host did not resolve: $host" }
        }
}
