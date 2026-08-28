# Bedrock Converse Workshop

[한국어](README.ko.md) | English

This example is a thin consumer boundary for the upstream bluetape4k AWS Kotlin
Bedrock helpers. It teaches one request model for both non-streaming
`Converse` and streaming `ConverseStream` calls without requiring AWS
credentials on the default path.

## Consumer Contract

| API | Contract |
| --- | --- |
| `BedrockPrompt` | Keeps only a non-blank `modelId` and user `prompt`; model-specific request builders stay upstream. |
| `BedrockConverseService.converse` | Creates a client for one operation, maps the prompt with `userMessageOf`, returns `textOrEmpty()`, and closes the client. |
| `BedrockConverseService.stream` | Returns a cold `Flow<String>` of text deltas. Client creation and the native call happen only during collection. |
| Cancellation | Collector cancellation reaches the native stream and closes the operation-owned client. `CancellationException` and native failures keep their original identity. |
| Logging | Lifecycle logs contain no prompt, response, credential, endpoint, or token values. |

Each collection of `stream` is an independent request and client scope. The
service intentionally does not add buffering, retries, or a second caching
layer; those policies belong to the caller or an upstream helper.

The consumer keeps the dependency surface small:

```kotlin
implementation(libs.aws.kotlin.bedrock.runtime)
implementation(libs.bluetape4k.aws.kotlin)
```

The model-neutral helper source is maintained in the
[bluetape4k AWS Kotlin module](https://github.com/bluetape4k/bluetape4k-aws/tree/main/aws-kotlin).

## Execution Modes

| mode | behavior |
| --- | --- |
| `local` (default) | No client construction, credential lookup, or network call in the sample application; tests use a fake client. |
| `real-aws` | An embedding application may inject an explicit `BedrockRuntimeClient` factory. The sample application itself still only prints the opt-in hint. |

## Local Run

The default application is credential-free and performs no network call:

```bash
./gradlew :aws-bedrock-converse:test
./gradlew :aws-bedrock-converse:run
```

The test suite uses a fake `BedrockRuntimeClient` to verify request mapping for
two model identifiers, cold-flow behavior, cancellation/close ordering, native
failure identity, and input validation.

## Explicit AWS Opt-in

The module does not resolve a region or credentials implicitly. An embedding
application must opt in by constructing the upstream client factory explicitly:

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

`deltas` is populated only when the returned Flow is collected. The caller owns
the collection scope; cancellation closes the native stream before the
operation-owned client.

Use the standard AWS credential provider chain only in a controlled environment,
and pass a loopback or HTTPS endpoint when an emulator is required. The sample
application accepts `-Dbluetape4k.aws.bedrock.mode=real-aws` only as an explicit
operator signal; it still does not create a client or call AWS by itself.

Never place prompts, generated text, credentials, endpoint URLs, or raw SDK
responses in logs or test reports. Live AWS execution is outside the default
smoke and CI path and may incur provider charges.

## Verification

```bash
./gradlew :aws-bedrock-converse:compileKotlin
./gradlew :aws-bedrock-converse:compileTestKotlin
./gradlew :aws-bedrock-converse:test --tests '*BedrockConverseServiceTest'
```
