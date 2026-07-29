# Issue #81 Messaging/Redis 고급 디자인

날짜: 2026-05-26
저장소: `bluetape4k-workshop`
분기: `feat/issue-81-messaging-advanced`
이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/81

## 문제

Issue #81에서는 다음과 같은 프로덕션 형태의 메시징 및 분산 상태 예시를 요청합니다.

- `messaging/kafka`
- `messaging/kafka-reply`
- `redis/cluster-demo`
- `redis/redisson-examples`

예제에서는 request/reply, 재시도 또는 재생 동작, 분산 잠금 정확성, Redis 클러스터 동작, 실패 경로 테스트, 이벤트 흐름 및 lock/cache 조정 다이어그램, Testcontainers 또는 로컬 서비스 요구 사항 및 Bluetape4k 우선 설명을 보여야 합니다.

사용자는 또한 요구 사항을 명시적으로 강화했습니다. 즉, 원시 프레임워크 API 또는 Testcontainers 도우미뿐만 아니라 Bluetape4k Kafka, Lettuce 및 Redisson 모듈을 적극적으로 사용하는 것입니다. 이번 Spring Kafka 4 워크숍에서 해당 Kafka 요구 사항은 Spring Kafka 3.x `bluetape4k-kafka` 아티팩트가 아닌 호환 가능한 `bluetape4k-kafka4` 아티팩트에 매핑됩니다.

## 현재 증거

### 이전 작업

`docs/lessons/2026-05-24-issue-81-messaging-advanced.md`은 `redis/cluster-demo` 및 `redis/distributed-lock`에 대한 이전 README 강화를 기록합니다. 또한 `messaging/kafka`, `messaging/kafka-reply`, `redis/redisson-examples`가 ​​Issue #83에 부분적으로 포함되어 있음도 명시되어 있습니다.

현재 문서에는 이미 여러 다이어그램과 BT 기능 테이블이 포함되어 있지만 현재 빌드 파일에는 요청된 BT 모듈의 사용률이 낮은 것으로 표시됩니다.

- `messaging/kafka/build.gradle.kts`에는 Spring Kafka 3.x `implementation(libs.bluetape4k.kafka)` 별칭이 주석 처리되어 있습니다.
- `messaging/kafka-reply/build.gradle.kts`에는 Spring Kafka 3.x `implementation(libs.bluetape4k.kafka)` 별칭이 주석 처리되어 있습니다.
- `redis/cluster-demo/build.gradle.kts`은 원시 `lettuce.core`을 사용하지만 `bluetape4k-lettuce`은 선언하지 않습니다.
- `redis/redisson-examples/build.gradle.kts`은 `bluetape4k-redis`과 원시 Redisson을 사용하지만 `bluetape4k-redisson`은 선언하지 않습니다.
- `redis/distributed-lock/build.gradle.kts`은(는) 이미 `bluetape4k-redis`과 `bluetape4k-redisson`을 선언했습니다.
- `gradle/libs.versions.toml`은 이미 명시적 버전 없이 `bluetape4k-kafka`, `bluetape4k-lettuce` 및 `bluetape4k-redisson` 별칭을 선언했지만 필요한 Spring Kafka 4 호환 `bluetape4k-kafka4` 별칭은 아직 선언하지 않았습니다.
- 실제로 가져온 BOM은(는) `io.github.bluetape4k:bluetape4k-dependencies:1.1.3`이며, 이전 지침 텍스트에 언급된 오래된 값이 아닙니다.

### CodeGraph 및 소스 검사

CodeGraph은(는) 이 작업 트리에 대해 초기화되었으며 1,403개의 색인화된 파일, 17,552개의 노드 및 34,425개의 에지를 보고합니다. 관련 현재 진입점은 다음과 같습니다.

- `messaging/kafka/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/controller/GreetingController.kt`
- `messaging/kafka/src/main/kotlin/io/bluetape4k/workshop/messaging/kafka/listener/GreetingMessageHandler.kt`
- `messaging/kafka-reply/src/main/kotlin/io/bluetape4k/workshop/kafka/ping/PingController.kt`
- `messaging/kafka-reply/src/main/kotlin/io/bluetape4k/workshop/kafka/pong/PongHandler.kt`
- `redis/cluster-demo/src/main/kotlin/io/bluetape4k/workshop/redis/cluster/RedisClusterApplication.kt`
- `redis/cluster-demo/src/main/kotlin/io/bluetape4k/workshop/redis/cluster/service/NumberService.kt`
- `redis/distributed-lock/src/main/kotlin/io/bluetape4k/workshop/lock/service/SuspendingFencedInventoryService.kt`
- `redis/redisson-examples/src/test/kotlin/io/bluetape4k/workshop/redisson/AbstractRedissonTest.kt`

현재 사용량:

- Kafka 모듈은 이미 `KafkaServer.Launcher.kafka`을 사용하고 있지만 기본 전송 경로는 여전히 원시 `KafkaTemplate.send(...)`을 호출합니다.
- `PingController`은 `ReplyingKafkaTemplate.sendAndReceive(...)`, Bluetape4k `onSuccess` / `onFailure` 및 코루틴 `await()`을 사용합니다.
- `RedisClusterApplication`은 `RedisClusterServer.Launcher.redisCluster`과 `RedisClusterServer.Launcher.LettuceLib.clientResources(redisCluster)`를 사용합니다.
- `NumberService`은 Spring Data Redis `clusterConnection` 및 Bluetape4k 바이트 변환 도우미를 사용하지만 `bluetape4k-lettuce` API는 사용하지 않습니다.
- `SuspendingFencedInventoryService`은 `unlockAsync(lockId).await()` 주위에 `RedissonClient.getLockId`, `tryLockAsync(..., lockId)`, `tokenAsync.await()`, `withContext(NonCancellable)`를 사용합니다.
- `redis/redisson-examples`은 `RedissonCodecs`, `streamAddArgsOf`, 코루틴 `getLockId`을 사용하지만 아티팩트 종속성은 `bluetape4k-redisson`를 통해 명시적으로 이루어져야 합니다.

### Bluetape4k API 형제 소스의 증거

`bluetape4k-projects` 아래의 현재 형제 라이브러리 소스는 다음을 보여줍니다.

- `bluetape4k-kafka4`은 Spring Kafka 4 호환 `KafkaOperations.suspendSend(...)` 확장과 `sendFlowAsParallel(...)` / `sendAndForget(...)` 흐름 도우미를 제공합니다.
- `bluetape4k-kafka4`은 문자열, 바이트 배열, Jackson, Kryo, Fory 및 압축 바이너리 코덱에 대한 `KafkaCodecs`도 제공합니다.
- `bluetape4k-lettuce`은 `LettuceClients`, `LettuceLongCodec`, `LettuceJsonCodecs`, `LettuceBinaryCodec`, `RedisFuture.awaitSuspending()`, `awaitAll()`, `withPipeline(...)`, `LettuceLock`, `LettuceSuspendLock`, `LettuceSuspendAtomicLong`을 제공합니다.
- `bluetape4k-redisson`은 `redissonClient {}`, `redissonClientForHighConcurrency(...)`, `RedissonCodecs`, `RedissonCacheConfig`, `localCachedMap(...)`, `mapCache(...)`, `streamAddArgsOf(...)` 및 코루틴 `RFuture` 도우미를 제공합니다.

### 현재 테스트 및 문서 드리프트

기존 Redis 분산 잠금 테스트에서는 이미 과잉 판매 방지, 안전한 취소 잠금 해제, 오래된 토큰 거부 및 잠금 획득 실패를 다루고 있습니다. 하지만:

- `DistributedLockTest.kt` 수입 `kotlin.test.assertFailsWith`; 새로운 변경 사항은 Bluetape4k 어설션 API를 사용해야 합니다.
- `SuspendFencedLockTest.kt`은(는) `SuspendedJobTester().workers(20).rounds(20)`를 올바르게 설명합니다: `totalUnits = rounds x blockCount`; `workers`은 동시성만 제어합니다.
- `redis/distributed-lock/README.md` 및 `README.ko.md`에는 Before/After 예에서 독립적인 `SuspendedJobTester` 드리프트가 포함되어 있습니다. 이는 `workers x rounds` 또는 `.rounds(1)`와 "20회 시도"를 의미합니다. 올바른 수식은 `rounds x blockCount`입니다.
- `redis/distributed-lock/README.ko.md`은 영어 README의 `Used bluetape4k Features` 및 Before/After 콘텐츠와 완전한 동등성이 부족합니다.

## 외부 참조 증거

Spring Kafka, Redisson 및 Spring Data Redis에 대해 Context7을 시도했지만 월별 할당량 오류를 반환했습니다. 공식 웹 대체가 사용되었습니다.

- Spring Kafka은 `ReplyingKafkaTemplate.sendAndReceive(...)`, `sendFuture`, 응답 시간 초과 동작 및 `@SendTo` request/reply 사용법을 문서화합니다.
- Redis아들 문서 `RFencedLock` 보호 서비스에 의한 펜싱 토큰 및 오래된 토큰 거부.
- Spring Data Redis는 Redis 클러스터 구성 및 클러스터 동작을 문서화합니다.
- Lettuce는 적응형 토폴로지 새로 고침 트리거와 동적 새로 고침 소스를 문서화합니다.

## 제약

- `bluetape4k-workflow` 유형 A 전체 디자인과 `bluetape4k-design`을 엄격하게 사용하세요.
- 행복한 경로나 실행 가능한 smoke/test 경로에서 `bluetape4k-kafka4`, `bluetape4k-lettuce`, `bluetape4k-redisson`를 적극적으로 사용하세요.
- 기존 Bluetape4k 유틸리티가 있는 경우 일반 프레임워크 래퍼를 추가하지 마십시오.
- `.codegraph/`을 커밋하지 마세요. 로컬 인덱스 상태가 생성됩니다.
- 공개 GitHub, PR, 커밋 및 KDoc 아티팩트를 영어로 유지합니다.
- 내부 사양, 계획, 강의는 한국어 또는 영어로 제공될 수 있습니다. 이 사양에서는 간결한 검토를 위해 영어를 사용합니다.
- Testcontainers 지원 테스트는 순차적으로 실행되어야 합니다.
- README 변경 사항은 현지화된 파일이 이미 존재하는 경우 현지화된 README 패리티를 유지해야 합니다.
- 새로운 종속성 버전 없음: `bluetape4k-dependencies` BOM 및 repo-local 카탈로그 별칭으로 관리되는 기존 별칭을 사용합니다.

## 구현 전 검증 게이트

구현이 시작되기 전에 계획은 다음 API 및 종속성 사실을 확인해야 합니다.

1. **버전 카탈로그 및 BOM**
   - `libs.bluetape4k.kafka4`, `libs.bluetape4k.lettuce`, `libs.bluetape4k.redisson`가 `gradle/libs.versions.toml`에 있는지 확인합니다.
   - 별칭이 없으면 `bluetape4k-kafka4 = { module = "io.github.bluetape4k:bluetape4k-kafka4" }`을 추가합니다.
   - 해당 별칭에 명시적인 버전이 없는지 확인합니다.
   - 루트 빌드가 `platform(libs.bluetape4k.dependencies)`을 가져오는지 확인합니다.
   - 이 저장소 카탈로그의 해결된 BOM 버전이 `bluetape4k-dependencies-version = "1.1.3"`인지 확인합니다.

2. **Kafka 확장 수신기**
   - 해결된 `bluetape4k-kafka4` 아티팩트가 `KafkaTemplate<String, Any>`와 호환되는 수신기 유형에서 `suspendSend`을 노출하는지 확인합니다.
   - 형제 소스에 `KafkaOperations<K, V>.suspendSend(...)`이 표시됩니다. 구현에서는 `KafkaTemplate`이 해결된 Spring Kafka 버전에 대해 `KafkaOperations`를 구현하는지 확인해야 합니다.
   - 해결된 아티팩트에 확장이 부족한 경우 Bluetape4k 이름으로 교체를 직접 롤링하지 마십시오. 간격을 기록하고 업스트림 문제가 발생할 때까지 기존 원시 Spring Kafka 경로를 유지합니다.

3. **상추 클러스터 경계**
   - `bluetape4k-lettuce`이 Redis 클러스터 인식 도우미를 노출하는지 확인합니다.
   - 형제 소스 증거는 현재 `RedisClient` 지향 `LettuceClients`, 코덱, 잠금, 원자 길이 및 미래 도우미를 보여줍니다. 클러스터 인식 도우미가 확인되지 않는 한 BT Lettuce 채택은 범위가 지정된 하위 수준 Redis 경로여야 하며 Spring Data Redis는 Redis 클러스터 예를 유지합니다.

4. **Redis아들 도우미 호환성**
   - `bluetape4k-redisson`이 해결된 아티팩트인 `redissonClient {}`, `RedissonCodecs`, `localCachedMap(...)`/`mapCache(...)`, `streamAddArgsOf(...)` 및 코루틴 향후 도우미에서 의도한 도우미를 제공하는지 확인합니다.

## 디자인 옵션

### 옵션 A: 종속성만 문서 정리

범위:

- Uncomment/add 요청된 BT 모듈 종속성.
- README 테이블을 업데이트하고 테스트 comments/docs을 수정합니다.

장점:

- 위험이 가장 낮습니다.

단점:

- 의미 있는 행복 경로 사용 없이 아티팩트가 나타나기 때문에 사용자의 명시적인 "적극적 사용" 요구 사항이 실패합니다.

### 옵션 B: 기존 예제에서 집중 BT 모듈 채택

범위:

- `messaging/kafka`: `bluetape4k-kafka4`에 의존하고 `GreetingController`의 원시 `KafkaTemplate.send(...)` 호출을 `KafkaOperations.suspendSend(...)`로 바꿉니다.
- `messaging/kafka-reply`: `bluetape4k-kafka4`에 의존합니다. 현재 BT API 증거가 직접적인 request/reply 대체를 표시하지 않기 때문에 request/reply에 대해 `ReplyingKafkaTemplate.sendAndReceive(...)`을 유지하세요. 공백을 솔직하게 기록하고 BT callback/coroutine 명의 도우미를 경로에 유지하세요.
- `redis/cluster-demo`: `bluetape4k-lettuce`에 의존하고 Testcontainers Redis 엔드포인트를 실행하는 새 service/test에서 `LettuceClients`, `LettuceLongCodec` 및 `awaitSuspending()`를 사용하는 것과 같은 작은 Lettuce 기반 클러스터 지원 경로를 추가합니다. 클러스터 인식 BT Lettuce 래퍼가 없는 경우 기존 Spring 데이터 Redis 클러스터 동작을 기본 클러스터 예로 유지합니다.
- `redis/redisson-examples`: `bluetape4k-redisson`에 명시적으로 의존하고 examples/docs을 포그라운드 `RedissonCodecs`, `redissonClient {}` 또는 동시성 클라이언트 도우미, `localCachedMap(...)`/`mapCache(...)`, `streamAddArgsOf(...)` 및 이미 적합한 코루틴 도우미로 업데이트합니다.
- `redis/distributed-lock`: 기존 `bluetape4k-redisson`/`bluetape4k-redis` 잠금 경로를 유지합니다. 코드 주석과 README 쌍의 어설션 스타일과 `SuspendedJobTester` 의미를 수정합니다.

장점:

- 대규모 새 하위 시스템 없이 명시적인 BT 모듈 채택 요청을 충족합니다.
- README 텍스트만 사용하는 것이 아니라 실행 가능한 증명을 개선합니다.
- 현재 모듈 경계와 예제를 재사용합니다.

단점:

- 교차 모듈 Gradle 검증 표면을 추가합니다.
- `bluetape4k-lettuce` 단일 노드 유틸리티와 기존 Redis 클러스터 예를 주의 깊게 구별해야 합니다.

### 옵션 C: 새로운 결합 Kafka + Redis 오케스트레이션 하위 시스템

범위:

- Kafka 이벤트 재생, Redis 잠금, 클러스터 쓰기 및 Redis아슨 캐시 근처 조정을 결합한 새로운 고급 모듈 또는 광범위한 시나리오를 추가합니다.

장점:

-  #81에 대한 가능한 가장 광범위한 해석.

단점:

- 이미 대부분의 사례가 있는 문제에 대해 이탈이 너무 많습니다.
- 일반적인 손으로 만든 코드의 위험이 더 높습니다.
- CI/nightly/module 등록 확인과 더 큰 검토 범위가 필요합니다.

## 선택된 접근법

옵션 B를 선택합니다.

옵션 A는 사용자의 명시적인 "적극적 사용" 요구 사항을 충족하지 않기 때문에 거부됩니다. 현재 증거가 기존 사례 내에서 집중된 채택을 지원하기 때문에 옵션 C는 거부됩니다.

## 필수 동작 변경 사항

### Kafka

- `implementation(libs.bluetape4k.kafka4)`을 `messaging/kafka`에 추가합니다.
- `GreetingController`의 원시 실행 후 잊어버리기 `kafkaTemplate.send(...)` 호출을 `kafkaTemplate.suspendSend(...)`로 바꾸세요.
- 구현 전 게이트에서 확인한 정확한 가져오기 및 수신자를 사용하세요.
- 컨트롤러 테스트 또는 컴파일 테스트가 BT 지원 경로를 입증하는지 확인합니다.
- `implementation(libs.bluetape4k.kafka4)`을 `messaging/kafka-reply`에 추가합니다.
- request/reply을 `ReplyingKafkaTemplate.sendAndReceive(...)`에 유지합니다. BT request/reply 확장이 없으면 래퍼를 만들기보다는 의도적인 경계로 이를 문서화하세요.
- 확인된 `bluetape4k-kafka4` request/reply 추상화가 해결된 아티팩트에 존재하지 않기 때문에 request/reply가 Spring Kafka의 `ReplyingKafkaTemplate`에 남아 있음을 설명하는 `Bluetape4k boundary` 섹션을 `messaging/kafka-reply/README.md`에 추가합니다.

### 상추

- `implementation(libs.bluetape4k.lettuce)`을 `redis/cluster-demo`에 추가합니다.
- `LettuceClients`과 BT 코덱 또는 코루틴 future 도우미를 사용하여 집중 실행 가능 테스트 경로를 추가합니다. 의도된 테스트 형태는 Testcontainers Redis 엔드포인트, `LettuceClients`, `LettuceLongCodec` 또는 `RedisFuture.awaitSuspending()`를 사용하고 write/read 왕복을 확인하는 새로운 클러스터 데모 테스트입니다.
- 소스 증거가 BT 도우미가 Redis 클러스터를 지원한다는 것을 입증하지 않는 한 Spring Data Redis 클러스터 동작을 클러스터를 인식하지 못하는 도우미로 바꾸지 마세요.
- 경계를 문서화합니다. `RedisClusterServer.Launcher.LettuceLib`은 Testcontainers 클러스터 클라이언트 리소스를 구성합니다. `bluetape4k-lettuce`은 낮은 수준의 Redis 작업을 위한 형식화된 코덱, 클라이언트 도우미 및 코루틴 향후 지원을 제공합니다.

### Redis아들

- `implementation(libs.bluetape4k.redisson)`을 `redis/redisson-examples`에 추가합니다.
- 다음과 같은 간단한 예에서는 BTRedis아들 도우미를 선호하세요.
  - 클라이언트 구성의 경우 `redissonClient {}` 또는 `redissonClientForHighConcurrency(...)`입니다.
  - `RedissonCodecs.*` 코덱 선택.
  - 옵션이 많은 캐시 예시의 경우 `localCachedMap(...)` 또는 `mapCache(...)`입니다.
  - 스트림 추가 예시의 경우 `streamAddArgsOf(...)`입니다.
  - 미래 컬렉션을 기다리는 코루틴 `RFuture` 도우미.
- 예제에서 구체적으로 원시 Redisson 객체를 가르치고 BT 도우미가 적절하지 않은 경우 원시 Redisson API를 유지하세요. 경계를 문서화하십시오.

### 분산 잠금 정리

- 현재 영향을 받는 모든 분산 잠금 테스트에서 `kotlin.test.assertFailsWith`을 승인된 Bluetape4k 어설션 가져오기로 대체합니다.
  - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/DistributedLockTest.kt`
  - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/FencedStaleHolderTest.kt`
  - `redis/distributed-lock/src/test/kotlin/io/bluetape4k/workshop/lock/LockFailureTest.kt`
- 올바른 `SuspendedJobTester` 주석 및 README 의미:
  - `workers(N).rounds(R).add { ... }`은 `R * blockCount` 총 실행을 의미합니다. `workers`는 동시성만 제어합니다.
  - `workers(20).rounds(20).add { ... }`은 등록된 블록 하나에 대해 400번이 아닌 20번의 시도를 의미합니다.
  - `workers(20).rounds(20)`을 20회 동시 스트레스 증명으로 유지하세요. 재고가 100이고 수량은 10이므로 예상되는 성공적인 공제는 10으로 유지됩니다. 나머지 시도는 초과 판매 없이 실패해야 합니다.
  - `README.md`에서 `SuspendedJobTester.workers(N).rounds(R) launches N workers each running R rounds`을 `rounds(R)`가 등록된 블록당 총 실행 수를 제어하고 `workers(N)`이 동시성을 제어한다는 문구로 바꾸세요.
  - `README.md`에서 `fixed workers x rounds = total attempts`을 `fixed rounds x blockCount = total attempts`로 바꾸고 Before/After 예제를 `.rounds(1) // ... 20 total attempts`에서 `.rounds(20) // 20 total attempts, exactly 10 succeed`로 변경합니다.
  - `README.ko.md`에 동일한 한국어 표현 수정을 적용합니다: `rounds(R)`는 등록된 `add { }` 블록당 총을 들고 있고 `workers(N)`는 그냥 성만 제어한다고 설명합니다.
  - 두 README 파일 모두에서 요약 예제를 `workers(20).rounds(20) -> 20 total attempts -> exactly 10 succeed`으로 유지합니다.
- `README.ko.md`를 영어 README의 BT 기능 적용 범위 및 종속성과 동등하게 만듭니다.

## 위험 및 실패 모드

1. **BT API 호환성 위험**: `bluetape4k-kafka4`, `bluetape4k-lettuce` 또는 `bluetape4k-redisson` API은 형제 소스와 이 워크숍에서 해결된 버전 간에 다를 수 있습니다.
   - 완화: 구현을 요청하기 전에 영향을 받는 모듈을 컴파일하세요. 증거가 아닌 설계 입력으로만 소스 증거를 사용하십시오.

2. **클러스터 경계 위험**: `bluetape4k-lettuce` 도우미는 단일 노드 유틸리티일 수 있지만 문제는 Redis 클러스터 동작을 요구합니다.
   - 완화: 클러스터 인식 BT API이 확인되지 않는 한 기존 Spring 데이터 Redis 클러스터 테스트를 유지하고 명확한 범위의 하위 수준 경로에 대해 BT Lettuce를 사용합니다.

3. **Kafka request/reply 초과 도달**: `ReplyingKafkaTemplate`에 대한 BT request/reply 추상화가 없을 수 있습니다.
   - 완화 방법: Spring Kafka request/reply을 프로토콜 프리미티브로 유지하고, 존재하는 경우 BT 도우미를 사용하고, 지원되지 않는 BT 간격을 명시적으로 문서화하세요.

4. **직렬화 보안 위험**: Kafka/Redis 코덱 예제는 실수로 안전하지 않은 다형성 역직렬화를 암시할 수 있습니다.
   - 완화: 유형별 코덱 예제와 문서 코덱 경계를 선호합니다. 명시적인 허용 목록 없이 개방형 다형성 역직렬화 또는 유형 헤더 역직렬화를 추가하지 마세요.

5. **Testcontainers 취약성**: Kafka/Redis 테스트에는 Docker가 필요하며 동시에 실행하면 안 됩니다.
   - 완화 방법: 대상 Testcontainers 지원 Gradle 명령을 순차적으로 실행합니다.

6. **생성된 로컬 인덱스 오염**: `.codegraph/`이 `git status`에 나타날 수 있습니다.
   - 완화 방법: 커밋에서 `.codegraph/`을 제외하고 커밋 전에 준비된 파일을 확인합니다.

## 수락 기준 매핑

| 이슈기준 | 현재 증거 | 폐쇄 예정 |
|---|---|---|
| Request/reply | `PingController`, `PongHandler`, Kafka 응답 README 및 다이어그램 | 봄을 유지하라 Kafka request/reply; BT 종속성 및 문서 BT 경계 추가 |
| 재시도 또는 재생 동작 | Redis아들 오래된 토큰 거부 및 read/write-through 예시 | Redis아들docs/tests을 지키고 강하게 하여라. 지원되지 않는 경우 재시도를 위조하지 마세요 |
| 분산 잠금 정확성 | `DistributedLockTest`, `FencedLockTest`, `SuspendFencedLockTest` | 검증문 스타일과 `SuspendedJobTester` 의미를 수정합니다. 테스트 재실행 |
| Redis 클러스터 동작 | `RedisClusterApplication`, `NumberServiceTest`, 클러스터 README | 클러스터 증거를 유지하십시오. 범위가 지정된 BT 양상추 사용 추가 |
| 실패 경로 테스트 | 잠금 획득 실패, 오래된 토큰 거부, 잠금 해제 취소, 재고 부족 | 보존 및 검증 |
| 시퀀스 다이어그램 | 기존 Kafka 응답, Kafka, Redis 클러스터, 분산 잠금, Redis아들 다이어그램 자산 | 새로운 흐름 변경에 필요한 경우에만 문서를 업데이트하세요 |
| Testcontainers/local 전제조건 | 기존 README 문서 Docker/Testcontainers | BT 모듈 사용이 변경되는 위치 명령 유지 및 업데이트 |
| 중고 Bluetape4k 기능 표 | 존재하지만 불완전하거나 여러 곳에 떠 있음 | 실제 코드 참조가 포함된 `bluetape4k-kafka4`, `bluetape4k-lettuce`, `bluetape4k-redisson` 행 추가 |
| BT지원 smoke/tests | Redis lock/cluster 테스트 및 Kafka 컨트롤러 경로 | 테스트 또는 컴파일 검사가 BT 지원 코드를 실행하는지 확인 |

## 완료의 정의

- 사양 및 계획은 `docs/superpowers/` 아래에 있습니다.
- 2-R단계와 3-R단계는 Claude 코드 CLI 아티팩트 및 P0/P1 = 0으로 통과됩니다.
- `messaging/kafka`은 전송 경로에서 `bluetape4k-kafka4` API를 사용합니다.
- `messaging/kafka-reply`은 true request/reply를 잘못 바꾸지 않고 `bluetape4k-kafka4` usage/boundary을 선언하고 문서화합니다.
- `redis/cluster-demo`은 Redis 클러스터 테스트를 유지하면서 범위가 지정된 하위 수준 Redis 경로에서 `bluetape4k-lettuce`을 선언하고 실행합니다.
- `redis/redisson-examples`은 `bluetape4k-redisson` 도우미를 선언하고 전경화합니다.
- 더 좁은 `redis-redisson-examples` 테스트 슬라이스는 추가되거나 변경된 BT 지원 코드 경로를 실행해야 합니다. 컴파일 전용 증명은 적극적으로 사용하기에는 충분하지 않습니다.
- `redis/distributed-lock` 주석과 README 쌍은 `SuspendedJobTester` 의미를 올바르게 설명합니다.
- `redis/distributed-lock/README.ko.md`은 터치된 영어 README와 패리티를 갖습니다.
- `kotlin.test.assertFailsWith`은 터치 테스트에서 제거되었습니다.
- 대상 확인이 순차적으로 통과됩니다.
  - `./gradlew :messaging-kafka:compileKotlin :messaging-kafka:compileTestKotlin`
  - `./gradlew :messaging-kafka-reply:compileKotlin :messaging-kafka-reply:compileTestKotlin`
  - `./gradlew :redis-cluster-demo:test`
  - `./gradlew :redis-distributed-lock:test`
  - `./gradlew :redis-redisson-examples:test` 또는 전체 모듈 런타임이 너무 넓은 경우 더 좁은 테스트 슬라이스(간격이 기록됨)
- `git diff --check` 통과.
- 6단계-R 코드 검토 게이트는 P0/P1 = 0으로 통과됩니다.
- 레슨 파일은 PR 생성 전에 작성 및 커밋됩니다.
- PR은(는) `Fixes #81`를 참조하고 DoD 증거를 포함하며 `debop`에 할당됩니다.
- CI은 병합 요청 이전에 전달됩니다.
