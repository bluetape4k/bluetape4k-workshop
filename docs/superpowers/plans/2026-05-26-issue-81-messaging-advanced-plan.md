# Issue #81 Messaging/Redis 고급 구현 계획

날짜: 2026-05-26
저장소: `bluetape4k-workshop`
분기: `feat/issue-81-messaging-advanced`
사양: `docs/superpowers/specs/2026-05-26-issue-81-messaging-advanced-design.md`
이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/81

## 게이트 상태

- 2단계 사양이 존재하며 Claude 2단계-R을 통과했습니다.
- 2단계-R 아티팩트: `.omx/artifacts/claude-step-2r-issue-81-spec-5min-final-20260526090610.md`
- 3-R 단계는 구현 전에 `P0=0` 및 `P1=0`을 통과해야 합니다.
- 4단계 구현 전에 이 사양과 계획을 커밋하세요.
- `.codegraph/`을 준비하거나 커밋하지 마세요.

## 구현 순서

### 작업 1: 종속성 및 API 확인

복잡성: 작음
위험: 빌드 호환성

1. `gradle/libs.versions.toml`에 다음이 있는지 확인합니다.
   - `bluetape4k-dependencies-version = "1.1.3"`
   - `bluetape4k-dependencies` `version.ref = "bluetape4k-dependencies-version"` 포함
   - `bluetape4k-lettuce` 명시적인 버전 없음
   - `bluetape4k-redisson` 명시적인 버전 없음
2. 없는 경우 기존 `bluetape4k-kafka` 별칭 옆에 `bluetape4k-kafka4 = { module = "io.github.bluetape4k:bluetape4k-kafka4" }`를 추가합니다.
3. 루트 빌드가 `platform(rootLibs.bluetape4k.dependencies)` 및 Spring Boot 4 BOM 가져오기를 확인합니다.
4. 해결된 `bluetape4k-kafka4` 아티팩트가 `KafkaTemplate<String, Any>`와 호환되는 `KafkaOperations<K, V>.suspendSend(...)`을 노출하는지 확인합니다.
5. 해결된 `bluetape4k-lettuce` 아티팩트가 선택한 하위 수준 API(`LettuceClients`, `LettuceLongCodec` 또는 `RedisFuture.awaitSuspending()`)을 제공하는지 확인합니다.
6. 해결된 `bluetape4k-redisson` 아티팩트가 선택한 `redissonClient` 또는 `redissonClientForHighConcurrency`, `RedissonCodecs`, `localCachedMap` 또는 `mapCache`, `streamAddArgsOf` 및 코루틴 `RFuture` 도우미를 제공하는지 확인합니다.

확인:

- 소스 검사가 충분하지 않은 경우 종속성 편집 후 종속성 통찰력 또는 대상 컴파일을 실행하세요.
- 선택한 BT API가 해결된 아티팩트에서 누락된 경우 교체를 수동으로 진행하는 대신 구현 전에 이 plan/spec을 중지하고 업데이트하세요.

### 작업 2: Kafka 전송 경로는 `bluetape4k-kafka4`을 사용합니다.

복잡성: 작음
위험: coroutine/send 의미

1. `messaging/kafka/build.gradle.kts`에서 주석 처리된 Spring Kafka 3 별칭을 `implementation(libs.bluetape4k.kafka4)`로 바꿉니다.
2. `GreetingController.kt`에서 원시 `KafkaTemplate.send(...)` 호출을 모두 `kafkaTemplate.suspendSend(...)`로 바꿉니다.
3. 검증된 `bluetape4k-kafka4` 확장 패키지를 가져옵니다.
4. 컨트롤러 서명을 일시 중지 인식 상태로 유지하고 `runBlocking`을 추가하지 마세요.
5. 주제 이름, 페이로드 유형 또는 리스너 동작을 변경하지 마십시오.

확인:

- `./gradlew :messaging-kafka:compileKotlin :messaging-kafka:compileTestKotlin`
- 확장 수신자 불일치로 인해 컴파일이 실패하는 경우 전송 경로 호출 변경 사항만 되돌리고 검토를 위해 종속성 변경 사항을 유지하고 해결된 API 간격으로 spec/plan을 업데이트합니다.

### 작업 3: Kafka request/reply 경계 문서 `bluetape4k-kafka4`

복잡성: 작음
위험: 문서의 정확성

1. `messaging/kafka-reply/build.gradle.kts`에서 주석 처리된 Spring Kafka 3 별칭을 `implementation(libs.bluetape4k.kafka4)`로 바꿉니다.
2. 봄 Kafka `ReplyingKafkaTemplate.sendAndReceive(...)`에는 `PingController`을 유지하세요.
3. 작업 1이 해결된 아티팩트에서 래퍼를 확인하지 않는 한 Bluetape4k request/reply 래퍼를 만들지 마세요.
4. 짧은 `Bluetape4k boundary` 섹션으로 `messaging/kafka-reply/README.md`을 업데이트합니다.
   - `bluetape4k-kafka4`은 Spring Kafka 4개 coroutine/send 도우미에 대해 제공됩니다.
   - 확인된 BT request/reply 추상화가 존재하지 않기 때문에 참 request/reply은 `ReplyingKafkaTemplate`에 남아 있습니다.
   - 기존 `onSuccess`, `onFailure` 및 코루틴 `await()` 사용법은 BT/coroutine 인체공학 이야기의 일부로 남아 있습니다.

확인:

- `./gradlew :messaging-kafka-reply:compileKotlin :messaging-kafka-reply:compileTestKotlin`
- README BT가 `ReplyingKafkaTemplate`를 대체하는 허위 검증이 없는지 검토하세요.

### 작업 4: Redis 클러스터 데모에서 실행 가능한 `bluetape4k-lettuce` 사용법을 얻습니다.

복잡성: 중간
위험: Testcontainers 및 클라이언트 수명주기

1. `implementation(libs.bluetape4k.lettuce)`을 `redis/cluster-demo/build.gradle.kts`에 추가합니다.
2. 기존 Spring Data Redis 클러스터 경로와 `RedisClusterServer.Launcher.LettuceLib.clientResources(redisCluster)`을 변경하지 않고 유지합니다.
3. Testcontainers Redis 엔드포인트에 대해 BT Lettuce 하위 수준 경로를 실행하려면 `AbstractRedisClusterTest` 확장 `redis/cluster-demo/src/test/kotlin/io/bluetape4k/workshop/redis/cluster/basic/Bluetape4kLettuceUsageTest.kt`을 추가합니다.
4. 다음을 선호하는 검증된 BT 양상추 API를 사용하세요:
   - client/command 생성을 위한 `LettuceClients`
   - 입력된 숫자 값의 경우 `LettuceLongCodec` 또는 코루틴 향후 브리징의 경우 `RedisFuture.awaitSuspending()`
5. 클라이언트 수명주기는 명시적이어야 합니다.
   - 테스트 범위에서 클라이언트 생성
   - 사용 후 상태 저장 연결 닫기
   - BT 도우미가 클라이언트 리소스를 소유하지 않은 경우 클라이언트 리소스를 종료합니다.
6. Testcontainers 지원 실행 직렬을 유지합니다. 병렬 테스트 가정을 도입하지 마십시오.
7. 경계를 설명하려면 present/needed인 경우 `redis/cluster-demo/README.md` 및 `README.ko.md`을 업데이트하세요.
   - Spring 데이터 Redis는 Redis 클러스터 증명으로 남아 있습니다.
   - `bluetape4k-lettuce`은 범위가 지정된 하위 수준 경로에 대한 형식화된 codecs/client helpers/coroutine 지원을 보여줍니다.

확인:

- `./gradlew :redis-cluster-demo:test`
- 새로운 테스트나 변경된 테스트는 단지 컴파일하는 것이 아니라 BT Lettuce 경로를 실행해야 합니다.

### 작업 5: Redis아들 예제가 명시적으로 전경에 있음 `bluetape4k-redisson`

복잡성: 중간
위험: 광범위한 테스트 모듈 런타임

1. `implementation(libs.bluetape4k.redisson)`을 `redis/redisson-examples/build.gradle.kts`에 추가합니다.
2. 기존 테스트에서는 집중적이고 이탈률이 낮은 변경을 선호합니다.
   - `redissonClient {}` 또는 `redissonClientForHighConcurrency(...)`가 현재 공유 수명 주기와 호환되고 `ShutdownQueue.register { shutdown() }`을 유지하는 경우에만 `AbstractRedissonTest`을 업데이트하세요.
   - 도우미가 동일한 옵션을 지원하는 경우 원시 `redisson.getLocalCachedMap(options)` 대신 확인된 BT `localCachedMap(...)` 또는 `mapCache(...)` 도우미를 사용하려면 `collections/LocalCachedMapExamples.kt`을 업데이트하세요.
   - 추가 인수에 확인된 BT `streamAddArgsOf(...)` 도우미를 사용하려면 `collections/StreamExamples.kt`을 업데이트하세요.
   - `AbstractRedissonTest` 또는 터치된 예제를 통해 `RedissonCodecs` 사용법을 볼 수 있도록 유지하세요.
3. 예제에서 원시 Redisson 개체 동작을 가르치는 경우 원시 Redisson API를 유지하세요.
4. `AbstractRedissonTest` 또는 터치 테스트에서 클라이언트 수명 주기가 명시적으로 유지되는지 확인합니다.
   - 테스트당 한 번씩 생성 class/scope
   - 기존 수명 주기 후크의 close/shutdown
5. `bluetape4k-redisson`에 대해 구체적인 사용 기능 행이 있는 present/needed인 경우 `redis/redisson-examples/README.md` 및 `README.ko.md`을 업데이트합니다.

확인:

- `./gradlew :redis-redisson-examples:test`를 선호하세요.
- 전체 모듈이 너무 광범위하거나 느린 경우 변경된 BT 지원 예제를 포함하는 더 좁은 Gradle 테스트 슬라이스를 실행하고 정확한 명령을 기록하세요.
- 새로운 활성 `bluetape4k-redisson` 사용에는 컴파일 전용 증명만으로는 충분하지 않습니다.

### 작업 6: 분산 잠금 정리 및 README 패리티

복잡성: 작음
위험: documentation/test 어설션 드리프트

1. `kotlin.test.assertFailsWith`을 승인된 `io.bluetape4k.assertions.assertFailsWith` 가져오기로 교체:
   - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/DistributedLockTest.kt`
   - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/FencedStaleHolderTest.kt`
   - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/LockFailureTest.kt`
2. `SuspendFencedLockTest.kt` 주석 의미를 유지합니다.
   - `totalUnits = rounds * blockCount`
   - `workers`은 동시성만 제어합니다.
   - `workers(20).rounds(20)` 블록이 1개 있으면 20번의 시도를 의미합니다.
3. `redis/distributed-lock/README.md` 업데이트:
   - `SuspendedJobTester.workers(N).rounds(R) launches N workers each running R rounds of the add { } block`를 `SuspendedJobTester.workers(N).rounds(R) spawns N concurrent workers that cooperatively execute R rounds of each add { } block. totalUnits = R x blockCount.`로 바꾸세요.
   - `workers x rounds = total attempts`을 `rounds x blockCount = total attempts`로 교체
   - Before/After 예를 `.rounds(1) // ... 20 total attempts`에서 `.rounds(20) // 20 total attempts, exactly 10 succeed`로 변경하세요.
   - 상태 `workers(20).rounds(20) -> 20 total attempts -> exactly 10 succeed`
4. `redis/distributed-lock/README.ko.md`에도 동일한 의미 수정을 적용합니다.
   - `N개의 워커가 각각 R라운드의 add { } 블록을 실행합니다`를 `N개의 동시 워커가 협력하여 각 add { } 블록을 R라운드 실행합니다. totalUnits = R x blockCount.`로 바꾸세요.
5. 터치된 종속성 및 BT 기능 테이블 콘텐츠에 대해 `README.ko.md`을 패리티로 가져옵니다.
6. `MultithreadingTester` 문구를 `SuspendedJobTester`과 별도로 유지하세요. `DistributedLockTest`과 같은 `MultithreadingTester` 예제는 여전히 `workers x rounds` 의미를 사용할 수 있으며 `SuspendedJobTester`인 것처럼 다시 작성해서는 안 됩니다.

확인:

- `./gradlew :redis-distributed-lock:test`
- 터치된 테스트 가져오기 `kotlin.test.assertFailsWith`가 없는지 확인합니다.

### 작업 7: README 및 다이어그램 확인

복잡성: 작음
위험: 사용자가 직면하는 드리프트

1. 터치된 모듈에 대한 README 쌍을 검토합니다.
   - `messaging/kafka/README.md` 및 현지화된 변형(있는 경우)
   - `messaging/kafka-reply/README.md` 및 현지화된 변형(있는 경우)
   - `redis/cluster-demo/README.md` 및 `README.ko.md`(있는 경우)
   - `redis/redisson-examples/README.md` 및 `README.ko.md`(있는 경우)
   - `redis/distributed-lock/README.md` 및 `README.ko.md`
2. 코드 경로가 실제로 명명된 모듈을 사용하는 경우에만 `Used Bluetape4k Features` 행을 추가하거나 업데이트하세요.
3. 이벤트나 lock/cache 흐름이 크게 변경되지 않는 한 기존 다이어그램 이미지를 업데이트하지 마세요. 계획된 코드 변경은 API 채택 및 문서 경계 변경이지 새로운 흐름 의미가 아닙니다.
4. 각 파일의 기존 언어로 공개 README 텍스트를 유지합니다.

확인:

- 수동 콘텐츠 검토.
- `git diff --check`.

### 작업 8: 전체 대상 검증

복잡성: 중간
위험: Docker/Testcontainers 런타임

명령을 순차적으로 실행합니다.

```bash
./gradlew :messaging-kafka:compileKotlin :messaging-kafka:compileTestKotlin
./gradlew :messaging-kafka-reply:compileKotlin :messaging-kafka-reply:compileTestKotlin
./gradlew :redis-cluster-demo:test
./gradlew :redis-distributed-lock:test
./gradlew :redis-redisson-examples:test
git diff --check
```

`:redis-redisson-examples:test`이 너무 광범위하거나 불안정한 경우 변경된 BT 지원 예제를 실행하는 더 좁은 `--tests` 명령을 실행하고 최종 보고서 및 PR에 제한 사항을 기록합니다.

### 작업 9: 복습, 강의 및 PR

복잡성: 작음
위험: 작업 흐름 준수

1. Claude 코드 CLI 아티팩트 및 통합 P0/P1 테이블을 사용하여 6-R단계 코드 검토 게이트를 실행합니다.
2. P0/P1 발견 항목을 모두 수정하고 영향을 받은 확인을 다시 실행하세요.
3. 다음을 사용하여 `docs/lessons/2026-05-26-issue-81-messaging-advanced.md`을 추가합니다.
   - 문맥
   - 결정
   - 결과
   - 확인 증거
   - 미래 에이전트 안내
4. Lore 프로토콜 예고편을 통해 구현 및 강의를 커밋합니다.
5. `Fixes #81`를 참조하여 `debop`에 할당된 GitHub PR를 만듭니다.
6. 자동 병합을 사용하지 마세요. PR URL 및 CI 상태를 보고합니다.

## 검토 로그

### 3-R단계 Codex 관점

유물: `.omx/artifacts/codex-step-3r-issue-81-plan-perspectives-20260526091300.md`

결과: `P0=0 P1=0 P2=8 P3=0`

### 3-R단계 Claude 코드 오퍼스 어드바이저

초기 아티팩트: `.omx/artifacts/claude-step-3r-issue-81-plan-5min-20260526091049.md`

초기 결과: `P0=0 P1=1`; README 산문에서는 여전히 `SuspendedJobTester`을 매 라운드마다 실행되는 각 작업자로 설명하고 있다는 사실을 받아들였습니다.

최종 아티팩트: `.omx/artifacts/claude-step-3r-issue-81-plan-5min-final-20260526091541.md`

최종 결과: `P0=0 P1=0 Verdict=PASS`

### 3-R단계 통합

3-R단계가 종료됩니다. 필수 P1 계획 편집이 작업 6에 적용되었습니다.

- 협동조합 `SuspendedJobTester` 근로자에 ​​대한 정확한 영어 및 한국어 README 표현 대체.
- `MultithreadingTester` 의미론과의 명시적인 분리.

P0/P1 결과가 남아 있지 않습니다.
