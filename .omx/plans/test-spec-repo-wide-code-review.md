# Test Spec: Repository-Wide Code Review

## Verification Strategy

- 모듈별 타깃 테스트를 우선 실행한다.
- 새로 추가한 회귀 테스트는 변경 이유를 직접 고정해야 한다.
- 통합 검증은 이번 세션에서 수정된 모듈만 묶어서 재실행한다.

## Required Checks

- Redis:
    - `:redis-redisson-examples:test --tests "io.bluetape4k.workshop.redisson.RedissonClientConfigTest"`
    - `:redis-redisson-examples:test --tests "io.bluetape4k.workshop.redisson.collections.LocalCachedMapTest"`
- Exposed/WebFlux:
    -
    `:exposed-sql-webflux-coroutines:test --tests "io.bluetape4k.workshop.exposed.controller.ControllerCoroutineScopeTest"`
    - 관련 controller 테스트
- Spring Data JPA Querydsl:
    -
    `:spring-data-jpa-querydsl:test --tests "io.bluetape4k.workshop.jpa.querydsl.domain.repository.MemberRepositoryTest"`
- Virtual Threads MVC:
    -
    `:virtualthreads-spring-mvc-tomcat:test --tests "io.bluetape4k.workshop.virtualthread.tomcat.domain.repository.MemberRepositoryTest"`
- 추가 lifecycle 후보:
    - `:orders:test --tests "io.bluetape4k.workshop.gateway.orders.controller.ProductControllerScopeTest"`
    - `:spring-data-r2dbc-webflux:test --tests "io.bluetape4k.workshop.r2dbc.controller.UserControllerScopeTest"`
    -
    `:spring-data-r2dbc-webflux-exposed:test --tests "io.bluetape4k.workshop.exposed.r2dbc.controller.UserControllerScopeTest"`
    - `:messaging-kafka:test --tests "io.bluetape4k.workshop.messaging.kafka.controller.GreetingControllerScopeTest"`

## Pass Criteria

- 각 타깃 테스트는 실패 없이 통과해야 한다.
- 기존 disabled/pending 테스트는 새 regression으로 간주하지 않는다.
- 새 컴파일 오류나 런타임 예외가 없어야 한다.
