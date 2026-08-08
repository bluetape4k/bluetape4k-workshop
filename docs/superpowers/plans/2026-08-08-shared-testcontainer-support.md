# 예제 공통 Testcontainers 지원 통합 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redis 예제 테스트에 반복된 Testcontainers 보조 기능을 shared로 통합하고, 중복·유효성이 확인된 라이브러리 승격 후보를 bluetape4k-projects의 한국어 GitHub 이슈로 남긴다.

**Architecture:** shared에 공개 RedisTestSupport를 추가해 기존 RedisServer.Launcher.redis와 세 개의 동적 프로퍼티 키를 보존한다. spring-data/redis-examples는 shared를 테스트 의존성으로 사용하고 기존 로컬 helper를 삭제한다. 일반적인 PropertyExportingServer–Spring 브리지는 이번 저장소에 구현하지 않고, live source/issue 중복 검증 후 별도 라이브러리 이슈로 제안한다.

**Tech Stack:** Kotlin 2.4.0, Java 21, Gradle Kotlin DSL, Spring Test DynamicPropertyRegistry, Testcontainers, JUnit 5, detekt, GitHub CLI.

---

## 사전 조건과 공통 규칙

- 작업 디렉터리는 /Users/debop/work/bluetape4k/bluetape4k-workshop/.worktrees/shared-redis-test-support-20260808로 고정한다.
- 기본 worktree의 기존 docs/images/** 변경은 읽기만 하고 수정·정리·reset하지 않는다.
- Testcontainers를 사용하는 Gradle 검증은 동시에 실행하지 않는다. 한 명령이 끝난 뒤 다음 명령을 시작한다.
- 모든 문서와 GitHub 공개 문구는 한국어로 작성하고, 코드·명령·API 식별자는 원문을 유지한다.
- 매 단계에서 실패한 출력과 변경 범위를 확인한다. RED 실패가 예상과 다르면 구현으로 진행하지 않고 원인을 먼저 기록한다.
- 외부 이슈 생성은 마지막 live 중복 게이트를 통과한 경우에만 실행한다.

## 작업 1: shared API의 RED 계약 테스트 작성

- [ ] 작업 1 완료: 계약 테스트가 의도한 RED 상태를 확인한다.

**Files:**

- Add shared/src/test/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupportTest.kt

**Action:**

1. DynamicPropertyRegistry를 구현하는 테스트용 recording registry를 만든다. add 호출에서 supplier를 즉시 평가하고 이름과 값을 LinkedHashMap에 기록한다.
2. RedisTestSupport.registerRedisProperties(registry)를 호출하는 테스트를 작성한다.
3. 정확히 다음 세 키가 등록되는지 검증한다.
   - testcontainers.redis.host
   - testcontainers.redis.port
   - testcontainers.redis.url
4. 각 값이 RedisTestSupport.redis.host, .port, .url과 같은지 검증한다. 테스트는 컨테이너의 실제 시작을 직접 제어하지 않고 helper의 계약만 고정한다.

**RED command:**

~~~bash
./gradlew :shared:test --tests "io.bluetape4k.workshop.shared.testcontainers.RedisTestSupportTest" --no-daemon
~~~

**Expected:** RedisTestSupport가 아직 존재하지 않으므로 테스트 소스 컴파일이 실패한다. 실패가 다른 원인이라면 그 원인을 해결하거나 계획을 갱신한 뒤 진행한다.

**Rollback:** 테스트 파일만 제거하면 기존 소스에는 영향이 없다.

## 작업 2: shared 구현과 compileOnly 경계 추가

- [ ] 작업 2 완료: shared 계약 테스트가 GREEN이다.

**Files:**

- Add shared/src/main/kotlin/io/bluetape4k/workshop/shared/testcontainers/RedisTestSupport.kt
- Modify shared/build.gradle.kts

**Action:**

1. shared/build.gradle.kts에 compileOnly(libs.bluetape4k.testcontainers)를 추가한다. 기존 testImplementation(libs.bluetape4k.testcontainers)와 Spring Test compileOnly 선언은 유지한다.
2. 다음 계약의 공개 object를 구현한다.

~~~kotlin
object RedisTestSupport {
    val redis: RedisServer = RedisServer.Launcher.redis

    fun registerRedisProperties(registry: DynamicPropertyRegistry) {
        registry.add("testcontainers.redis.host") { redis.host }
        registry.add("testcontainers.redis.port") { redis.port }
        registry.add("testcontainers.redis.url") { redis.url }
    }
}
~~~

3. object와 함수에는 한국어 KDoc으로 목적, 기존 세 키, 소비 모듈의 테스트 범위를 설명한다. 불필요한 일반 DSL이나 호환 alias는 추가하지 않는다.
4. 작업 1의 focused test를 다시 실행해 GREEN을 확인한다.

**GREEN command:**

~~~bash
./gradlew :shared:test --tests "io.bluetape4k.workshop.shared.testcontainers.RedisTestSupportTest" --no-daemon
~~~

**Expected:** 새 계약 테스트가 통과하고 BUILD SUCCESSFUL이 출력된다.

**Rollback:** 작업 1의 테스트를 유지한 채 새 source와 compileOnly 한 줄을 되돌리면 RED 상태로 복귀한다. 소비 모듈 변경은 시작하지 않는다.

## 작업 3: Redis 예제 호출부를 shared API로 전환

- [ ] 작업 3 완료: 네 호출부가 shared API를 사용하고 local helper가 제거됐다.

**Files:**

- Modify spring-data/redis-examples/build.gradle.kts
- Modify spring-data/redis-examples/src/test/kotlin/io/bluetape4k/workshop/redis/AbstractRedisTest.kt
- Modify spring-data/redis-examples/src/test/kotlin/io/bluetape4k/workshop/redis/reactive/AbstractReactiveRedisTest.kt
- Modify spring-data/redis-examples/src/test/kotlin/io/bluetape4k/workshop/redis/stream/sync/SyncStreamApiTest.kt
- Modify spring-data/redis-examples/src/test/kotlin/io/bluetape4k/workshop/redis/stream/reactive/ReactiveStreamApiTest.kt
- Delete spring-data/redis-examples/src/test/kotlin/io/bluetape4k/workshop/redis/RedisTestSupport.kt

**Action:**

1. spring-data/redis-examples/build.gradle.kts의 dependencies에 testImplementation(project(":shared"))를 추가한다.
2. 네 호출부의 local package import를 io.bluetape4k.workshop.shared.testcontainers.RedisTestSupport로 바꾼다. 호출 방식과 @DynamicPropertySource 생명주기는 변경하지 않는다.
3. 네 파일에서 기존 helper의 사용이 새 object를 가리키는지 확인한다.
4. 로컬 중복 파일을 삭제한다. rg "RedisTestSupport|testcontainers.redis\\.(host|port|url)" spring-data/redis-examples shared로 남은 정의와 호출을 확인한다.
5. 생명주기·동시성 경계는 기존 RedisServer.Launcher.redis와 TestMutexService를 보존하는 것으로 고정하고, 두 Testcontainers 검증을 동시에 실행하지 않는다. Redis backend가 준비되지 않으면 첫 startup 오류를 원인으로 기록한다.

**Commands:**

~~~bash
./gradlew :shared:compileKotlin :spring-data-redis-examples:compileTestKotlin --no-daemon
./gradlew :spring-data-redis-examples:test --no-daemon
~~~

**Expected:** 두 compile task가 통과하고 Redis 영향 테스트가 기존과 동일한 컨테이너 프로퍼티로 통과한다. 컨테이너 오류가 나면 병렬 실행을 피하고 첫 실패 로그부터 진단한다.

**Rollback:** 영향 테스트가 실패하면 호출부 import와 testImplementation(project(":shared"))를 되돌리고, 로컬 helper를 복원한 뒤 원인을 분리한다. 성공 증거 없이 삭제 상태를 유지하지 않는다.

## 작업 4: shared README 두 locale 문서 동기화

- [ ] 작업 4 완료: 두 README의 계약과 의존성 경계가 일치한다.

**Files:**

- Modify shared/README.md
- Modify shared/README.ko.md

**Action:**

1. 기존 HTTP helper 설명을 보존하면서 RedisTestSupport의 패키지와 import 예시를 추가한다.
2. testcontainers.redis.host, testcontainers.redis.port, testcontainers.redis.url의 등록 계약과 @DynamicPropertySource 사용 예시를 설명한다.
3. helper가 저장소 내부 테스트 지원 API이며 소비 모듈이 testImplementation(project(":shared"))와 자체 실행 의존성을 선언해야 함을 문서화한다.
4. English/Korean README가 동일한 계약·범위·의존성 경계를 설명하는지 비교한다.

**Commands:**

~~~bash
rg -n "RedisTestSupport|testcontainers.redis.(host|port|url)|testImplementation\\(project\\(\\":shared\\"\\)\\)" shared/README.md shared/README.ko.md
git diff --check
~~~

**Expected:** 두 문서에서 helper, 세 키, 사용 범위, 의존성 예시가 모두 확인되고 whitespace 오류가 없다.

**Rollback:** README 변경만 되돌리며 코드 통합 상태는 유지한다. locale 간 계약이 다르면 먼저 문서를 맞춘다.

## 작업 5: 영향 범위 표준 검증

- [ ] 작업 5 완료: 표준 검증 결과를 기록했다.

**Files:**

- No additional source changes unless a verified test/build failure requires a narrow correction.

**Action and commands (sequential):**

~~~bash
./gradlew :shared:test --no-daemon
./gradlew :spring-data-redis-examples:test --no-daemon
./gradlew detekt --no-daemon
./gradlew :shared:dependencies --configuration testRuntimeClasspath --no-daemon
./gradlew projects --no-daemon
git diff --check
~~~

**Expected:** :shared:test baseline plus new contract test가 통과하고 Redis 영향 테스트, detekt, dependency graph, project registration, diff check가 모두 성공한다. 환경상 실행 불가한 항목은 명령과 이유를 기록하며 통과로 주장하지 않는다.

**Rollback:** 실패한 검증의 최초 오류를 기준으로 해당 단계의 최소 수정만 수행한다. 광범위한 정리나 dependency version 변경은 하지 않는다.

**Repository hazard scope:** 새 모듈·이동 모듈·publishable library 모듈을 추가하지 않으므로 settings 등록, smoke/full workflow group, validation matrix, stale-check, Kover aggregation 변경은 N/A로 확인한다. Redis 예제는 기존 full/container-backed 그룹에 남긴다.

## 작업 6: 라이브 라이브러리 후보 중복·유효성 게이트와 이슈 생성

- [ ] 작업 6 완료: 이슈 URL 또는 N/A 근거가 live 상태로 검증됐다.

**Scope:** /Users/debop/work/bluetape4k/bluetape4k-projects, GitHub bluetape4k/bluetape4k-projects

**Action:**

1. 현재 develop source에서 PropertyExportingServer, propertyNamespace, properties()와 Spring DynamicPropertyRegistry bridge의 최신 상태를 다시 확인한다.
2. gh issue list -R bluetape4k/bluetape4k-projects --state all --search로 DynamicPropertyRegistry, PropertyExportingServer, testcontainers property helper, RedisServer properties를 각각 검색한다.
3. 후보가 중복되지 않고 SDK-neutral testcontainers 본체와 선택적 Spring Test 연동의 모듈 경계가 유효하다는 근거를 기록한다.
4. 조건을 만족할 때만 다음 한국어 이슈를 생성한다. 중복 또는 유효성 부족이면 생성하지 않고 N/A 사유를 기록한다.

~~~bash
rg -n "interface PropertyExportingServer|class RedisServer|DynamicPropertyRegistry|propertyNamespace|fun properties" /Users/debop/work/bluetape4k/bluetape4k-projects/testing/testcontainers/src
gh issue list -R bluetape4k/bluetape4k-projects --state all --search "DynamicPropertyRegistry"
gh issue list -R bluetape4k/bluetape4k-projects --state all --search "PropertyExportingServer"
gh issue list -R bluetape4k/bluetape4k-projects --state all --search "testcontainers property helper"
gh issue list -R bluetape4k/bluetape4k-projects --state all --search "RedisServer properties"
gh label list -R bluetape4k/bluetape4k-projects --limit 100
~~~

~~~bash
gh issue create -R bluetape4k/bluetape4k-projects \
  --title "[testcontainers] PropertyExportingServer의 Spring DynamicPropertyRegistry 연동 승격 검토" \
  --label enhancement --label test --label infra/io \
  --body $'## 배경\n\nbluetape4k-workshop의 여러 Spring/Testcontainers 예제에서 서버의 host/port/url을 DynamicPropertyRegistry에 등록하는 보조 코드가 반복되어 shared로 통합했습니다.\n\n## 현재 근거\n\n testing/testcontainers의 PropertyExportingServer는 propertyNamespace와 properties()를 제공하지만, 현재 소스에서는 Spring DynamicPropertyRegistry 등록 브리지를 확인하지 못했습니다.\n\n## 제안\n\nPropertyExportingServer를 Spring Test에 직접 결합하지 않도록 선택적 연동 모듈 또는 테스트 전용 확장으로 DynamicPropertyRegistry 등록 API를 검토해 주세요. 기존 namespace와 properties 계약을 재사용하고, 서버별 키 충돌과 supplier 평가 시점을 명시해야 합니다.\n\n## 비목표\n\nSDK-neutral testcontainers 본체에 Spring 의존성을 추가하거나 모든 소비자의 프로퍼티 키를 강제하는 변경은 포함하지 않습니다.\n\n## 수용 기준\n\n- 현재 서버별 properties() 계약을 재사용한다.\n- Spring Test 의존성 경계가 본체와 분리된다.\n- host/port/url 및 추가 프로퍼티의 등록·생명주기·충돌 동작을 테스트한다.\n- 기존 testcontainers 소비자의 의존성 그래프와 API 호환성을 보존한다.\n\n## 참고\n\n- bluetape4k-workshop의 shared RedisTestSupport 통합 커밋\n- testing/testcontainers의 PropertyExportingServer와 RedisServer 구현'
~~~

**Expected:** 이슈를 생성한 경우 URL, 제목, 라벨, 본문을 즉시 gh issue view NUMBER -R bluetape4k/bluetape4k-projects로 확인하고 기록한다. 생성하지 않은 경우 검색 결과와 source 근거를 N/A로 남긴다. 이 단계에서 코드 저장소는 수정하지 않는다.

**Rollback:** 외부 이슈는 코드 rollback 대상이 아니다. 잘못된 중복 판단이 사후 확인되면 해당 이슈에 한국어 정정 댓글을 남기고 후속 라이브러리 구현은 중단한다. 이슈 삭제·수정은 별도 승인 없이 하지 않는다.

## 작업 7: 최종 리뷰, 커밋, DoD

- [ ] 작업 7 완료: Lore 커밋과 검증 기반 DoD를 작성했다.

**Action:**

1. git diff --stat, git diff --check, git status --short로 변경 범위와 기본 worktree 보호를 확인한다.
2. Kotlin checklist, repository common gates, verification-before-completion 결과를 확인하고 다음 acceptance traceability를 채운다.
   - 설계 목표 1–2 → 작업 1–3 및 영향 테스트
   - 설계 목표 3 → 작업 1, 2, 5
   - 설계 목표 4 및 library gate → 작업 6
   - 설계 목표 5 → 작업 4
   - 설계 비목표와 rollback → 작업 3, 6, 7
3. 공개 API의 KDoc, README locale parity, compileOnly 경계, 삭제된 local duplicate, exact property keys를 최종 확인한다.
4. 추가 dependency가 없고 shared의 compileOnly와 소비 모듈의 testImplementation 경계가 dependency graph에서 확인되는지 검토한다.
5. 구현 파일과 문서를 함께 Lore protocol 커밋으로 기록한다. 커밋에는 의도, 제약, 거부한 대안, 검증 명령, 미검증 항목을 한국어로 포함한다.
6. PR·merge·release는 사용자 승인 범위에 없으므로 생성하지 않는다. 최종 DoD에는 PR/merge/release를 N/A로 명시한다.

**Commands:**

~~~bash
git diff --stat
git diff --check
git status --short
git log -1 --oneline
~~~

**Expected:** 소스·문서 변경만 의도한 파일에 있고 diff check가 통과하며, 테스트·detekt·의존성·이슈 검증 근거가 최종 보고에 연결된다.

**Rollback:** 커밋 전에는 파일 단위 revert만 사용하고 unrelated dirty asset에는 손대지 않는다. 커밋 후 구현 rollback은 별도 승인된 revert 커밋으로만 수행한다.

## 수용 기준 추적표

| 설계 수용 기준 | 실행 단계 | 증거 |
|---|---|---|
| shared에 Redis helper가 한 번만 존재 | 1–3 | rg 정의 검색, diff |
| 네 호출부 전환 및 local 파일 제거 | 3 | import 검색, Redis test |
| 세 프로퍼티 계약 유지 | 1–2, 5 | contract test, 영향 test |
| shared/Redis 테스트 통과 | 2, 3, 5 | Gradle 결과 |
| Kotlin/common gate와 diff check | 5, 7 | checklist 및 명령 출력 |
| 승격 후보 중복·유효성 확인 및 조건부 이슈 | 6 | live source/issue 검색과 issue URL 또는 N/A 근거 |
| 두 README locale parity | 4, 7 | rg, diff review |
| 기본 worktree dirty asset 보존 | 사전 조건, 7 | 전후 git status 비교 |
