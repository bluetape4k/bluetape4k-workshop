# 예제 공통 Testcontainers 지원 통합 설계

## 1. 결정 요약

Redis 통합 테스트에서 반복되는 Testcontainers 서버와 Spring 동적 프로퍼티 등록 보조 기능을 `shared` 모듈로 이동한다. 기존 프로퍼티 키와 싱글턴 생명주기는 유지하고, `spring-data/redis-examples`는 `shared`의 공개 테스트 보조 API를 사용한다.

현재 저장소에서 확인된 `PropertyExportingServer` 기반 프로퍼티 등록 반복은 별도 라이브러리 승격 후보로 검증한다. 후보가 현재 `bluetape4k-projects`의 이슈·소스와 중복되지 않을 때만 `bluetape4k/bluetape4k-projects`에 한국어 GitHub 이슈를 생성한다. 외부 라이브러리 구현 자체는 이번 작업에 포함하지 않는다.

이번 설계는 2026-08-08 사용자의 WF-03 실행계획 승인에 근거한다.

## 2. 현재 근거

- `spring-data/redis-examples/src/test/kotlin/io/bluetape4k/workshop/redis/RedisTestSupport.kt`가 `RedisServer.Launcher.redis`를 소유하고 다음 세 키를 등록한다.
  - `testcontainers.redis.host`
  - `testcontainers.redis.port`
  - `testcontainers.redis.url`
- 같은 로컬 보조 객체를 `AbstractRedisTest`, `AbstractReactiveRedisTest`, `SyncStreamApiTest`, `ReactiveStreamApiTest`가 반복 사용한다.
- `shared`는 이미 HTTP 테스트 확장과 Testcontainers 기반 테스트를 제공하며, 여러 예제 모듈이 `testImplementation(project(":shared"))`를 사용한다.
- `bluetape4k-projects/testing/testcontainers`의 `PropertyExportingServer`는 서버별 `properties()` 맵과 `propertyNamespace`를 이미 제공한다. 현재 Spring `DynamicPropertyRegistry` 브리지 API는 확인되지 않았다.
- 11개의 `NettyConfig.kt`는 공통 골격 외에 타임아웃, `SO_LINGER`, event-loop 수가 다르다. 교육용 설정의 의도적 차이를 잃을 수 있으므로 이번 `shared` 통합 대상에서 제외한다.
- 과거 Ktor 승격 제안은 현재 `bluetape4k-projects/ktor/core`, `ktor/testing` 등으로 구현되어 있어 새 중복 이슈 후보로 취급하지 않는다.

## 3. 목표와 비목표

### 목표

1. Redis 예제 테스트의 공통 Testcontainers 보조 기능을 `shared` 한 곳에서 관리한다.
2. 기존 프로퍼티 키, 값, 시작 시점, 테스트 동작을 보존한다.
3. 공통 API에 직접 회귀 테스트를 추가하고 영향 모듈을 순차 검증한다.
4. 외부 라이브러리 승격 후보를 live 중복·유효성 검증 후 한국어 GitHub 이슈로 남긴다.
5. `shared/README.md`와 `shared/README.ko.md`에 새 공개 helper와 의존성 경계를 함께 문서화한다.

### 비목표

- `bluetape4k-projects` 라이브러리 코드를 이번 저장소에서 직접 수정하지 않는다.
- 모든 `DynamicPropertyRegistry` 등록을 하나의 추상화로 강제하지 않는다.
- 교육 목적의 `NettyConfig`, raw `WebTestClient` 호출, 도메인별 PostgreSQL 설정을 일괄 변환하지 않는다.
- 새 외부 의존성이나 새 workshop 모듈을 추가하지 않는다.

## 4. 대안과 선택

### 대안 A — Redis 보조 객체를 `shared`로 이동 (선택)

`shared/src/main/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupport.kt`에 공개 `RedisTestSupport`를 두고, Redis 예제에 `testImplementation(project(":shared"))`를 추가한다. 기존 호출부는 새 패키지만 import한다.

장점은 변경 범위가 좁고 기존 계약을 그대로 보존하며, `shared`의 현재 테스트 유틸리티 역할과 맞는다는 점이다. 단점은 Redis 전용 API가 `shared`에 추가된다는 점이며, 사용 범위를 현재 Redis 예제로 한정해 관리한다.

### 대안 B — `shared`에 모든 Testcontainers의 일반 동적 프로퍼티 DSL 추가

`PropertyExportingServer`와 유사한 일반 맵 등록 DSL을 먼저 만들고 Redis/PostgreSQL/기타 예제를 동시에 변환한다.

공통성은 높지만 Spring Test와 Testcontainers 타입의 결합 범위가 커지고, 서로 다른 프로퍼티 키·추가 설정·생명주기를 억지로 통합할 위험이 있어 이번 작업에서는 기각한다. 후속 라이브러리 이슈의 설계 검토 대상으로만 남긴다.

### 대안 C — workshop에 중복을 남기고 외부 라이브러리 이슈만 생성

구현 위험은 가장 낮지만 사용자가 요청한 예제 공통 기능 통합을 충족하지 못하므로 기각한다.

## 5. 구현 경계와 계약

새 API의 계약은 다음과 같다.

```kotlin
object RedisTestSupport {
    val redis: RedisServer

    fun registerRedisProperties(registry: DynamicPropertyRegistry)
}
```

- `redis`는 기존과 같이 `RedisServer.Launcher.redis`를 사용한다.
- `registerRedisProperties`는 기존 세 키를 동일한 값으로 등록한다.
- API는 `shared`를 의존하는 다른 테스트 모듈에서 사용할 수 있도록 `internal`이 아닌 공개 심볼로 둔다.
- 공개 object와 함수에는 저장소 언어 정책에 맞는 한국어 KDoc과 실제 사용 예시를 둔다.
- `shared`의 `bluetape4k-testcontainers`와 Spring Test 타입은 `compileOnly` 경계를 유지한다. 소비 모듈은 실행에 필요한 자체 의존성을 계속 선언한다.
- 기존 로컬 파일은 제거하고 호출부의 import만 변경한다. 별도 호환 alias는 만들지 않는다.

## 6. 테스트 전략

1. `shared`에 프로퍼티 등록 계약 테스트를 먼저 추가한다. 등록된 키 집합과 값 형식을 검증하고, 실제 Redis 컨테이너 시작은 현재 `RedisServer.Launcher` 생명주기를 사용하는 영향 모듈 테스트에서 확인한다.
2. RED 단계에서 새 API가 없어서 컴파일/테스트가 실패하는지 확인한다.
3. 최소 구현 후 `:shared:test`를 GREEN으로 만든다.
4. Redis 예제의 순차 테스트에서 Spring context가 새 API의 세 프로퍼티를 읽는지 검증한다.
5. `git diff --check`, targeted compile/test, detekt를 실행하고 Testcontainers 명령은 동시에 실행하지 않는다.

## 7. 라이브러리 승격 후보 검증

다음 후보를 `bluetape4k-projects` 이슈로 검토한다.

- 후보: `PropertyExportingServer`의 서버 프로퍼티를 Spring `DynamicPropertyRegistry`에 등록하는 선택적 테스트 브리지.
- 중복 근거: workshop의 Redis 보조 객체와 여러 Spring/Testcontainers 테스트의 동일한 host/port/url 등록 패턴.
- 유효성 근거: 현재 라이브러리에 서버별 `properties()` 계약은 있지만 Spring 브리지는 없다.
- 중복 방지: 이슈 생성 직전에 `gh issue list/view`로 open/all 상태를 다시 검색하고, 현재 `develop` 소스에 동일 API가 없는지 확인한다.
- 이슈 생성 조건: 중복이 아니고, SDK-neutral 본체와 선택적 Spring 테스트 연동의 모듈 경계가 수용 가능하다는 근거가 있을 때만 생성한다. 조건이 충족되지 않으면 이슈를 만들지 않고 N/A 근거를 보고한다.

## 8. 장애 모드와 완화

| 위험 | 신호 | 완화 |
|---|---|---|
| 프로퍼티 키 또는 값 변경 | Redis context 시작 실패, URI 불일치 | 기존 세 키를 계약 테스트와 영향 테스트에서 직접 비교 |
| `compileOnly` 누락 | shared 컴파일은 통과하지만 소비 모듈 컴파일 실패 | Redis 모듈에 자체 `bluetape4k-testcontainers`/Spring Test 의존성을 확인하고 `:shared:compileKotlin`과 소비 모듈 compile을 함께 실행 |
| 컨테이너 생명주기 변화 | Docker 시작 실패 또는 테스트 간 충돌 | 기존 `RedisServer.Launcher.redis`와 TestMutex를 유지하고 Testcontainers 검증을 직렬 실행 |
| 라이브 이슈 중복 | 기존 open/closed 이슈가 동일 범위를 포함 | 생성 직전 GitHub 검색과 최신 source 확인, 중복이면 생성 중단 |
| 사용자 dirty 산출물 오염 | 기본 worktree의 `docs/images/**` diff 변화 | 격리 worktree만 수정하고 원래 worktree 상태를 전후 비교 |

## 9. 수용 기준과 완료 정의

- [ ] Redis 공통 보조 기능이 `shared`에 한 번만 존재한다.
- [ ] 네 Redis 테스트 호출부가 새 API를 사용하고 로컬 중복 파일은 제거된다.
- [ ] 기존 `testcontainers.redis.host/port/url` 계약이 유지된다.
- [ ] `:shared:test`와 Redis 영향 테스트가 통과한다.
- [ ] Kotlin checklist, 저장소 common gates, `git diff --check`가 PASS 또는 근거 있는 N/A다.
- [ ] 라이브러리 후보에 대해 중복·유효성 근거가 있고, 조건 충족 시 한국어 GitHub 이슈 URL이 기록된다.
- [ ] `shared`의 English/Korean README가 새 helper와 사용 범위를 동일하게 설명한다.
- [ ] 기본 worktree의 기존 다이어그램 변경은 보존된다.

## 10. 롤백

공통 API 이동과 호출부 변경을 동일한 커밋 단위로 되돌리면 기존 로컬 `RedisTestSupport.kt`를 복원할 수 있다. 외부 GitHub 이슈는 코드 롤백 대상이 아니며, 잘못된 중복 판단이 확인되면 이슈에 정정 댓글을 남기고 후속 구현은 중단한다.

## 11. 설계 self-review

- Placeholder/TODO를 사용하지 않았다.
- 선택한 API, 의존성 경계, 테스트 순서가 서로 모순되지 않는다.
- Netty와 도메인별 DB 설정을 제외한 이유가 명시되어 있다.
- 외부 이슈 생성은 live 중복 확인이라는 조건부 단계로 제한되어 있다.
