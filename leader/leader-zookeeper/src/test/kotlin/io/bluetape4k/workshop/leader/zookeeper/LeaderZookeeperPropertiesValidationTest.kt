package io.bluetape4k.workshop.leader.zookeeper

import io.bluetape4k.assertions.assertFailsWith
import io.bluetape4k.workshop.leader.zookeeper.config.LeaderZookeeperProperties
import org.junit.jupiter.api.Test

/**
 * T9: [LeaderZookeeperProperties] `init {}` 검증 테스트이다.
 *
 * Spring context와 ZooKeeper 컨테이너를 사용하지 않는 순수 단위 테스트이다.
 * 잘못된 설정이 운영에 도달하지 못하도록 생성자가 유효하지 않은 구성을 조기에 거부하는지 검증한다.
 */
class LeaderZookeeperPropertiesValidationTest {

    @Test
    fun `blank basePath throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderZookeeperProperties(basePath = "")
        }
    }

    @Test
    fun `zero groupMaxLeaders throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderZookeeperProperties(groupMaxLeaders = 0)
        }
    }

    @Test
    fun `blank connectString throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            LeaderZookeeperProperties(
                zookeeper = LeaderZookeeperProperties.ZooKeeperConfig(connectString = "")
            )
        }
    }
}
