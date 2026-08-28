# #741 Bedrock Converse/ConverseStream consumer 설계

## 문제와 목표

현재 `aws/` workshop에는 AWS 0.5.0의 model-neutral Bedrock
`Converse`/`ConverseStream` Kotlin API를 소비하는 예제가 없다. 새
`aws/bedrock-converse` 모듈은 다음 경계를 실행 가능한 코드와 문서로
보여준다.

- provider/model 이름과 무관한 텍스트 요청 매핑
- 단일 응답과 스트리밍 응답의 분리
- 수집할 때만 SDK 호출이 발생하는 cold `Flow`
- collector 취소가 native stream과 client 종료까지 전파되는 수명주기
- 기본 경로는 credential-free fake이며 실제 AWS 호출은 명시적으로 opt-in

범위는 workshop consumer 예제다. upstream `bluetape4k-aws` API 자체의
재시도, exactly-once, provider별 보장은 추가하지 않는다.

## 근거와 제약

- 대상 이슈: [#741](https://github.com/bluetape4k/bluetape4k-workshop/issues/741)
- upstream `converseStreamFlow`, `textDeltaFlow`, `converse`,
  `bedrockRuntimeClientOf`, `withBedrockRuntimeClient`를 현재
  `/Users/debop/work/bluetape4k/bluetape4k-aws`에서 확인했다.
- 모델 요청 builder는 upstream `userMessageOf`와
  `converseRequestOf`/`converseStreamRequestOf`를 사용한다.
- workshop은 `settings.gradle.kts`의 자동 2-level module 등록을 사용한다.
- Java SDK v2는 root AWS BOM이 버전을 관리하고, AWS Kotlin SDK는 현재
  catalog의 `aws-kotlin` 호환성 라인을 사용한다.
- 실제 AWS credential과 비용이 발생하는 호출은 CI 및 기본 실행에 포함하지
  않는다.

## 선택한 접근

### 모듈 경계

`aws/bedrock-converse`는 `application` plugin을 사용하는 독립 Kotlin
consumer다. public learning surface는 다음 두 타입으로 제한한다.

```kotlin
data class BedrockPrompt(
    val modelId: String,
    val prompt: String,
)

class BedrockConverseService(
    private val clientFactory: () -> BedrockRuntimeClient,
)
```

`BedrockConverseService`는 매 호출마다 factory가 반환한 client를
`useSafe` 범위에서 사용한다.

- `suspend fun converse(prompt: BedrockPrompt): String`는
  `client.converse(...).textOrEmpty()`를 호출한다.
- `fun stream(prompt: BedrockPrompt): Flow<String>`는 `flow {}` 내부에서
  client를 만들고 `client.converseStreamFlow(...).textDeltaFlow()`를
  `emitAll`한다.
- 따라서 `stream()` 호출 자체는 SDK를 호출하지 않으며 collection마다 한
  번 호출한다. 수집이 끝나거나 취소되면 client가 닫힌다.

production factory는 `bedrockRuntimeClientOf(endpointUrl, region,
credentialsProvider)`를 사용한다. endpoint가 지정되지 않은 기본 설정은
실제 AWS credential provider chain을 요구하는 `real-aws` opt-in에서만
사용한다. 테스트 factory는 MockK client를 반환하므로 네트워크와 credential
해석을 수행하지 않는다.

### 대안 검토

1. **loopback Bedrock emulator** — 실제 wire contract에 가깝지만 표준
   Bedrock emulator가 없고 Testcontainers 시작 비용과 SDK HTTP fixture가
   학습 목표보다 커진다. 기본 검증 경로에서 제외한다.
2. **provider-neutral 자체 SPI** — 향후 provider 교체에는 유리하지만
   upstream `bluetape4k-aws-kotlin`의 request/Flow helper를 가리고 새
   추상화 계약을 유지해야 한다. consumer 예제의 범위를 넘으므로 제외한다.
3. **직접 SDK 호출 + fake client factory (선택)** — 실제 upstream API와
   client lifecycle을 그대로 드러내고, fake가 lazy/cancellation 계약을
   결정적으로 검증한다. 선택한다.

## 요청·응답 계약

- `modelId`와 `prompt`는 blank를 허용하지 않는다. upstream
  `requireNotBlank` 계약을 그대로 사용한다.
- user message는 `userMessageOf(prompt.prompt)`로 만들며, request의
  `modelId`와 `messages`는 helper가 소유한다.
- 단일 응답은 텍스트 블록을 순서대로 합쳐 반환하고 비텍스트 블록은
  upstream `textOrEmpty` 의미에 따라 건너뛴다.
- 스트림은 텍스트 델타 순서를 유지하고 빈 델타는 유지하며 비텍스트
  이벤트는 `textDeltaFlow` 의미에 따라 건너뛴다.
- 서비스는 retry, buffering, replay, parallel mapping, payload logging을
  제공하지 않는다.

## 실패·취소·보안

1. SDK 예외는 원본 identity를 보존해 호출자에게 전달한다. 서비스가
   일반 예외로 감싸지 않는다.
2. `CancellationException`은 broad catch 대상이 아니다. stream collection
   취소 시 upstream Flow의 `finally`가 실행되고 client `close()`가 한 번
   호출되는지 테스트한다.
3. endpoint가 설정될 때 upstream factory의 HTTPS/literal-loopback HTTP
   검증을 사용한다. 일반 HTTP는 허용하지 않는다.
4. prompt, response text, credential, endpoint secret은 로그에 쓰지 않는다.
   기본 application 출력은 모듈/모드 안내만 제공한다.

## 검증 계획

- request mapping: 두 model id에 대해 non-stream/stream request가 올바른
  model과 user message를 갖는지 확인한다.
- cold Flow: `stream()` 생성 시 native invocation 0회, 두 번 collection 시
  invocation 2회인지 확인한다.
- cancellation/lifecycle: 실제 `Job` 취소로 stream finally와 client close
  순서를 확인하고 `close()` 정확히 한 번을 검증한다.
- failure identity: native SDK 예외가 동일 instance로 전달되는지 확인한다.
- module contract: credential-free unit test와 `./gradlew :aws-bedrock-converse:test`
  를 실행한다. live AWS smoke는 별도 명시적 명령으로만 문서화한다.
- registration: `./gradlew projects`, AWS smoke/full workflow 목록,
  `docs/coverage-matrix.md`, `aws/README.md`/`README.ko.md`를 함께
  확인한다.

## 완료 조건

- [ ] `aws/bedrock-converse`가 자동 등록되고 catalog alias와 consumer
      dependency가 중앙 버전 정책을 따른다.
- [ ] non-streaming/streaming request mapping과 두 model 형태가 테스트된다.
- [ ] cold Flow, 취소 전파, client lifecycle 및 원본 예외 계약이 테스트된다.
- [ ] 기본 테스트가 credential 없이 통과하고 실제 AWS 실행은 opt-in이다.
- [ ] retry/exactly-once/provider-specific guarantee를 과장하지 않는다.
- [ ] smoke/full workflow, validation matrix, 한·영 README가 동기화된다.

## DoD

구현 후 targeted test → module test → `git diff --check` → `./gradlew
projects` → workflow/matrix/readme 링크 검증 순서로 완료 증거를 수집한다.
PR은 `feat/aws-bedrock-converse-741`에서 #847 head를 base로 생성하고,
최종 train에서만 rebase merge한다.
