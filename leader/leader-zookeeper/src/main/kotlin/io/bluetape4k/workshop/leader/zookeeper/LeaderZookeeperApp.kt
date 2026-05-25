package io.bluetape4k.workshop.leader.zookeeper

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Workshop application demonstrating ZooKeeper-based distributed leader election.
 *
 * ## Behavior / Contract
 * Runs four scheduled services, each using a different [io.bluetape4k.leader.LeaderElector] variant:
 * - [service.BlockingLeaderService] — blocking single-leader
 * - [service.SuspendLeaderZkService] — coroutine single-leader
 * - [service.GroupLeaderService] — blocking group-leader
 * - [service.SuspendGroupLeaderService] — coroutine group-leader
 *
 * All electors share one [org.apache.curator.framework.CuratorFramework] bean.
 * See [config.LeaderZookeeperConfig] for ZooKeeper connection lifecycle.
 */
@SpringBootApplication
@EnableScheduling
class LeaderZookeeperApp

fun main(args: Array<String>) {
    runApplication<LeaderZookeeperApp>(*args)
}
