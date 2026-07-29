package io.bluetape4k.workshop.leader

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Leader Election 워크숍 application입니다.
 *
 * `bluetape4k-leader`(LettuceLeaderElector)를 사용한 distributed leader election을 보여줍니다.
 * multi-instance 배포에서는 특정 시점마다 하나의 instance만 scheduled job을 실행합니다.
 */
@SpringBootApplication
@EnableScheduling
class LeaderElectionApp

fun main(args: Array<String>) {
    runApplication<LeaderElectionApp>(*args)
}
