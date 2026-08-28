# Bedrock Converse 워크숍

[English](README.md) | 한국어

이 예제는 upstream bluetape4k AWS Kotlin Bedrock helper를 얇은 consumer
경계로 사용하는 방법을 보여줍니다. 기본 경로에는 AWS 자격 증명이 필요하지
않으며, 하나의 request model로 non-streaming `Converse`와 streaming
`ConverseStream`을 함께 다룹니다.

## Consumer 계약

| API | 계약 |
| --- | --- |
| `BedrockPrompt` | non-blank `modelId`와 사용자 `prompt`만 보유하고, 모델별 request builder는 upstream에 둡니다. |
| `BedrockConverseService.converse` | 작업마다 client를 만들고 `userMessageOf`로 prompt를 매핑한 뒤 `textOrEmpty()`를 반환하고 client를 닫습니다. |
| `BedrockConverseService.stream` | text delta만 내보내는 cold `Flow<String>`을 반환합니다. client 생성과 native 호출은 collection 시점에만 수행합니다. |
| Cancellation | collector 취소가 native stream으로 전파되고 작업이 소유한 client가 닫힙니다. `CancellationException`과 native failure는 원래 identity를 유지합니다. |
| Logging | lifecycle log에 prompt, response, credential, endpoint, token 값을 기록하지 않습니다. |

`stream`을 collection할 때마다 독립된 request와 client scope를 만듭니다.
이 서비스는 buffering, retry, 별도 caching layer를 추가하지 않으며 해당
정책은 caller 또는 upstream helper가 소유합니다.

consumer의 dependency surface는 작게 유지합니다.

```kotlin
implementation(libs.aws.kotlin.bedrock.runtime)
implementation(libs.bluetape4k.aws.kotlin)
```

model-neutral helper의 원본은
[bluetape4k AWS Kotlin module](https://github.com/bluetape4k/bluetape4k-aws/tree/main/aws-kotlin)에서
관리합니다.

## 실행 모드

| mode | 동작 |
| --- | --- |
| `local` (기본값) | 샘플 application은 client 생성, credential 조회, network call을 수행하지 않으며 테스트는 fake client를 사용합니다. |
| `real-aws` | embedding application이 명시적인 `BedrockRuntimeClient` factory를 주입할 수 있습니다. 샘플 application 자체는 여전히 opt-in 안내만 출력합니다. |

## 로컬 실행

기본 application은 credential-free이며 network call을 수행하지 않습니다.

```bash
./gradlew :aws-bedrock-converse:test
./gradlew :aws-bedrock-converse:run
```

테스트는 fake `BedrockRuntimeClient`로 두 model identifier의 request mapping,
cold Flow 동작, cancellation과 close 순서, native failure identity, 입력 검증을
확인합니다.

## 명시적 AWS Opt-in

모듈은 region이나 credential을 암묵적으로 해석하지 않습니다. embedding
application이 upstream client factory를 명시적으로 구성해야 합니다.

```kotlin
val service = BedrockConverseService {
    bedrockRuntimeClientOf(region = "ap-northeast-2")
}

val answer = service.converse(
    BedrockPrompt(modelId = "amazon.nova-lite", prompt = "Say hello"),
)

val deltas = service.stream(
    BedrockPrompt(modelId = "amazon.nova-lite", prompt = "Stream hello"),
).toList()
```

반환된 Flow를 collection할 때만 `deltas`가 채워집니다. caller가 collection
scope를 소유하며, cancellation은 native stream을 먼저 닫고 작업 단위 client를
닫습니다.

통제된 환경에서만 표준 AWS credential provider chain을 사용하고, emulator가
필요하면 loopback 또는 HTTPS endpoint를 전달합니다. 샘플 application은
`-Dbluetape4k.aws.bedrock.mode=real-aws`를 명시적 운영 신호로만 받아들이며,
그 자체로 client를 만들거나 AWS를 호출하지 않습니다.

prompt, 생성 text, credential, endpoint URL, raw SDK response를 log나 test
report에 기록하지 않습니다. 실제 AWS 실행은 기본 smoke와 CI 경로 밖에 있으며
provider 비용이 발생할 수 있습니다.

## 검증

```bash
./gradlew :aws-bedrock-converse:compileKotlin
./gradlew :aws-bedrock-converse:compileTestKotlin
./gradlew :aws-bedrock-converse:test --tests '*BedrockConverseServiceTest'
```
