package io.bluetape4k.workshop.leader.zookeeper

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * ZooKeeper 기반 분산 리더 선출을 시연하는 워크숍 애플리케이션이다.
 *
 * ## 동작 / 계약
 * 서로 다른 [io.bluetape4k.leader.LeaderElector] 변형을 사용하는 네 개의 스케줄 서비스를 실행한다.
 * - [service.BlockingLeaderService] - 블로킹 단일 리더
 * - [service.SuspendLeaderZkService] - 코루틴 단일 리더
 * - [service.GroupLeaderService] - 블로킹 그룹 리더
 * - [service.SuspendGroupLeaderService] - 코루틴 그룹 리더
 *
 * 모든 elector는 하나의 [org.apache.curator.framework.CuratorFramework] 빈을 공유한다.
 * ZooKeeper 연결 생명주기는 [config.LeaderZookeeperConfig]를 참고한다.
 */
@SpringBootApplication
@EnableScheduling
class LeaderZookeeperApp

fun main(args: Array<String>) {
    runApplication<LeaderZookeeperApp>(*args)
}
