# 예제 공통 Testcontainers 지원 통합 lesson

## 맥락

Redis 예제 테스트 네 곳이 동일한 RedisServer.Launcher.redis와 Spring DynamicPropertyRegistry 등록 코드를 각자 보유하고 있었다. shared 모듈은 이미 repository-internal 테스트 helper의 경계를 제공하고 있었다.

## 결정

RedisTestSupport를 shared main source로 이동하고, Redis 소비 모듈은 testImplementation(project(":shared"))를 선언한다. bluetape4k-testcontainers와 Spring Test는 소비 모듈이 계속 소유하며, 기존 세 property key와 singleton lifecycle을 보존한다. PropertyExportingServer의 Spring bridge는 workshop에 구현하지 않고 라이브 중복·유효성 확인 후 bluetape4k-projects issue #1321로 승격 후보를 기록했다.

## 발견과 대응

- RED focused test에서 새 symbol unresolved reference를 확인한 뒤 최소 구현으로 GREEN을 만들었다.
- 계획의 축약 Gradle 경로 :spring-data:redis-examples가 ambiguous임을 실제 project tree에서 발견해 :spring-data-redis-examples로 고쳤다. 이후 compile/test가 통과했다.
- README 사용 예시에 package와 Spring annotation import가 빠질 수 있어 두 locale에 실제 import를 보강했다.
- native review lane은 bounded wait에서 응답하지 않아 main-session six-lens fallback으로 교체했고, P0/P1 blocker는 없었다.

## 검증

- :shared:cleanTest :shared:test --no-daemon --no-build-cache: 40 tests, failures 0, skipped 0
- :spring-data-redis-examples:test --no-daemon: 41 tests, failures 0, skipped 3
- :shared:compileKotlin :spring-data-redis-examples:compileTestKotlin: BUILD SUCCESSFUL
- detekt: BUILD SUCCESSFUL
- shared testCompileClasspath dependency graph: bluetape4k-testcontainers와 spring-test 확인, BUILD SUCCESSFUL
- projects와 git diff --check: PASS
- 라이브 source HEAD df754135d85891aa643b0a0070ff0fcb65577532, issue/label 중복 확인 후 https://github.com/bluetape4k/bluetape4k-projects/issues/1321 생성 및 view 검증

## 미래 guard

- Spring DynamicPropertyRegistry bridge를 bluetape4k-testcontainers에 추가할 때 SDK-neutral 본체와 선택적 Spring Test 경계를 분리한다.
- 새 shared test helper는 exact property contract test, 한국어 KDoc, English/Korean README 예시, 소비 모듈 dependency graph를 함께 추가한다.
- Gradle 자동 등록 모듈은 실제 project name을 projects 명령으로 확인한 뒤 계획에 기록한다.
- Testcontainers 영향 검증은 별도 Gradle 프로세스 간 mutex 한계를 고려해 항상 직렬 실행한다.
