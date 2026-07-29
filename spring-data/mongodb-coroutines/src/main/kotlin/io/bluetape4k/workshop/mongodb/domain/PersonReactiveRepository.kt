package io.bluetape4k.workshop.mongodb.domain

import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.Tailable
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface PersonReactiveRepository: ReactiveCrudRepository<Person, String> {

    fun findPersonByFirstname(firstname: String): Mono<Person>

    fun findFirstByFirstname(firstname: String): Mono<Person>

    fun findAllByFirstname(firstname: String): Flux<Person>

    @Query("{ 'firstname': ?0, 'lastname': ?1 }")
    fun findByFirstnameAndLastname(firstname: String, lastname: String): Mono<Person>

    fun findByLastname(lastname: String): Flux<Person>

    /**
     * [lastname] 으로 조회하는 파생 쿼리입니다.
     * [lastname] 은 매개변수 값을 얻기 위해 blocking 하지 않아도 되는 지연 해석을 사용합니다.
     *
     * @param lastname 조회할 성을 담은 비동기 값입니다.
     * @return 성이 일치하는 [Person] 스트림입니다.
     */
    fun findByLastname(lastname: Mono<String>): Flux<Person>

    /**
     * [firstname] 과 [lastname] 으로 조회하는 파생 쿼리입니다.
     * [firstname] 은 매개변수 값을 얻기 위해 blocking 하지 않아도 되는 지연 해석을 사용합니다.
     *
     * @param firstname 조회할 이름을 담은 비동기 값입니다.
     * @param lastname 조회할 성입니다.
     * @return 이름과 성이 모두 일치하는 [Person] 입니다.
     */
    fun findByFirstnameAndLastname(firstname: Mono<String>, lastname: String): Mono<Person>

    /**
     * capped collection 에 새 엔티티가 기록될 때마다 tailable cursor 로 엔티티 스트림을 방출합니다.
     *
     * 참고: [MongoDB Tailable cursors](https://www.mongodb.com/docs/manual/core/tailable-cursors/)
     */
    @Tailable
    fun findWithTailableCursorBy(): Flux<Person>
}
