package io.bluetape4k.workshop.leader.zookeeper.config

import io.bluetape4k.logging.KLogging
import io.bluetape4k.support.requireNotBlank
import io.bluetape4k.support.requirePositiveNumber
import org.springframework.boot.context.properties.ConfigurationProperties
import java.io.Serializable
import java.time.Duration

/**
 * ZooKeeper 기반 리더 선출 워크숍 모듈의 설정 프로퍼티이다.
 *
 * ## 동작 / 계약 (R16 - ZooKeeper에는 TTL이 없다)
 * ZooKeeper는 리더 선출에 세션에 묶인 ephemeral znode를 사용한다.
 * Redis 기반 선출과 다른 점은 다음과 같다.
 * - lease TTL이 없으며, ZooKeeper 세션이 만료되거나 명시적으로 닫힐 때까지 리더십을 유지한다.
 * - 이 클래스에는 `leaseTime`과 `autoExtend`가 의도적으로 없다.
 *   `LeaderElectionOptions.autoExtend = true`를 설정하면 WARN 로그를 남기고 조용히 무시한다.
 * - 네트워크 분리나 프로세스 크래시 등으로 세션이 만료되면 ephemeral 선출 노드가 자동으로 제거되고,
 *   경쟁 후보 사이에서 재선출이 발생한다.
 * - 허용 가능한 failover 지연 시간에 맞춰 작업 주기 대비 [ZooKeeperConfig.sessionTimeoutMs]를 조정한다.
 *
 * ## 운영 고려사항
 * - 기본 [ZooKeeperConfig.connectString]은 개발 전용인 `localhost:2181`이다.
 * - 기본 [ZooKeeperConfig.sessionTimeoutMs]는 60 000 ms이며, 강제 종료 시 failover 대기 구간이 된다.
 * - 이 워크숍에서는 ACL과 TLS를 생략한다. 운영 배포에서는 `CuratorFrameworkFactory.builder()`로
 *   ACLProvider와 SASL/TLS를 구성해야 한다.
 */
@ConfigurationProperties(prefix = "leader.zookeeper")
data class LeaderZookeeperProperties(
    val zookeeper: ZooKeeperConfig = ZooKeeperConfig(),
    val basePath: String = "/workshop/leader-zookeeper",
    val waitTime: Duration = Duration.ofSeconds(2),
    val groupMaxLeaders: Int = 2,
    val jobFixedDelay: String = "PT10S",
    val suspendJobFixedDelay: String = "PT12S",
    val groupJobFixedDelay: String = "PT15S",
    val suspendGroupJobFixedDelay: String = "PT18S",
    // 참고: R16에 따라 ZooKeeper에는 TTL이 없으므로 leaseTime / autoExtend를 의도적으로 두지 않는다.
) : Serializable {
    companion object : KLogging() {
        private const val serialVersionUID = 1L
    }

    init {
        basePath.requireNotBlank("basePath")
        groupMaxLeaders.requirePositiveNumber("groupMaxLeaders")
        // connectString은 ZooKeeperConfig.init 내부에서 검증한다.
    }

    /**
     * ZooKeeper 연결 설정이다.
     *
     * @property connectString ZooKeeper 연결 문자열이다. `host:port` 또는 `host:port,host:port,...` 형식을 사용한다.
     * @property sessionTimeoutMs ZooKeeper 세션 타임아웃을 밀리초 단위로 지정한다. 크래시 시 failover 지연 시간을 좌우한다.
     * @property connectionTimeoutMs ZooKeeper 연결 수립 타임아웃을 밀리초 단위로 지정한다.
     * @property blockUntilConnectedSeconds 초기 ZooKeeper 연결을 기다릴 최대 시간을 초 단위로 지정한다.
     */
    data class ZooKeeperConfig(
        val connectString: String = "localhost:2181",
        val sessionTimeoutMs: Int = 60_000,
        val connectionTimeoutMs: Int = 15_000,
        val blockUntilConnectedSeconds: Long = 10,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }

        init {
            connectString.requireNotBlank("connectString")
            sessionTimeoutMs.requirePositiveNumber("sessionTimeoutMs")
            connectionTimeoutMs.requirePositiveNumber("connectionTimeoutMs")
            blockUntilConnectedSeconds.requirePositiveNumber("blockUntilConnectedSeconds")
        }
    }
}
