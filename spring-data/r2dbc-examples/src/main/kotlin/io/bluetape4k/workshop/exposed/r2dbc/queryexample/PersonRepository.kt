package io.bluetape4k.workshop.exposed.r2dbc.queryexample

import org.springframework.data.repository.query.ReactiveQueryByExampleExecutor
import org.springframework.data.repository.reactive.ReactiveCrudRepository

/**
 * Person repository
 *
 * Query by Example 예제를 위해 [ReactiveQueryByExampleExecutor] 를 상속받습니다.
 */
interface PersonRepository: ReactiveCrudRepository<Person, Int>, ReactiveQueryByExampleExecutor<Person> {
}
