package io.bluetape4k.workshop.leader

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Leader Election Workshop Application.
 *
 * Demonstrates distributed leader election using `bluetape4k-leader` (LettuceLeaderElector).
 * In a multi-instance deployment, only one instance will execute scheduled jobs at any given time.
 */
@SpringBootApplication
@EnableScheduling
class LeaderElectionApp

fun main(args: Array<String>) {
    runApplication<LeaderElectionApp>(*args)
}
