# AWS Settings Boundary 워크숍

[English](README.md) | 한국어

이 예제는 upstream bluetape4k AWS Kotlin의 Secrets Manager와 Systems
Manager Parameter Store wrapper를 하나의 provider-neutral settings boundary
뒤에서 소비합니다. 기본 sample과 테스트는 credential-free입니다.

## Consumer 계약

| API | 계약 |
| --- | --- |
| `SettingsSource` | provider exception을 노출하지 않고 한 key를 `Found`, `Missing`, `Denied`로 resolve합니다. |
| `SecretsManagerSettingsSource` | upstream `getSecretString`을 작업 단위 `useSafe` client scope 안에서 사용합니다. |
| `ParameterStoreSettingsSource` | upstream `getSecureParameter`를 작업 단위 `useSafe` client scope 안에서 사용합니다. |
| `SettingsResolver.startup` | 새 설정 결과를 만들며 missing/denied 결과의 기본 동작은 엄격한 `FAIL`입니다. |
| `SettingsResolver.refresh` | 이전 값을 병합하지 않는 full replacement 결과를 만들며 기본 동작은 `OMIT`입니다. |
| `SettingsSnapshot.redactedEntries` | redacted marker만 반환하며 `AwsSecretValue`가 payload를 log와 report에서 숨깁니다. |

source adapter는 provider별 not-found와 access-denied error를 공통 결과로
분류합니다. cancellation과 분류하지 못한 failure는 원래 identity를
유지합니다. 각 lookup은 client를 만들고 닫는 작업 범위를 소유합니다.

이 consumer는 retry, mutable cache, stale value fallback, 값을 포함한 metric
tag, HTTP response serialization을 추가하지 않습니다.

```kotlin
implementation(libs.aws.kotlin.secretsmanager)
implementation(libs.aws.kotlin.ssm)
implementation(libs.bluetape4k.aws.kotlin)
```

wrapper 원본은
[bluetape4k AWS Kotlin module](https://github.com/bluetape4k/bluetape4k-aws/tree/main/aws-kotlin)에서
관리합니다.

## Startup과 refresh

```kotlin
val source = SecretsManagerSettingsSource {
    secretsManagerClientOf(region = "ap-northeast-2")
}
val resolver = SettingsResolver(source)

val startup = resolver.startup(setOf("database/password"))
val refreshed = resolver.refresh(setOf("database/password"))
val safeView = refreshed.redactedEntries()
```

`startup`은 `Missing`과 `Denied`에서 기본적으로 fail-fast합니다. key 없이도
동작할 수 있는 caller는 `SettingsFallbackPolicy.omit()`를 지정합니다. 기본
refresh policy는 omit이지만 replacement 결과에 새 `Missing`/`Denied`
결과를 그대로 기록하며 이전 설정 결과를 복사하지 않습니다.

`ParameterStoreSettingsSource`는 secure parameter wrapper를 사용합니다.

```kotlin
val source = ParameterStoreSettingsSource {
    ssmClientOf(region = "ap-northeast-2")
}
```

실제 AWS 또는 emulator를 사용할 때는 명시적인 factory와 통제된 HTTPS 또는
literal-loopback endpoint를 전달합니다. sample application에서 credential을
해석하지 않으며 secret payload, credential, endpoint, raw SDK response를 log,
metric, test report, error response에 넣지 않습니다. 실제 AWS 실행은 기본
smoke와 CI 경로 밖에 있으며 provider 비용이 발생할 수 있습니다.

## 로컬 검증

```bash
./gradlew :aws-settings-boundary:test
./gradlew :aws-settings-boundary:build
./gradlew :aws-settings-boundary:run
```

테스트는 fake source loader와 fake client를 사용합니다. 두 provider의 성공,
누락, 권한 오류와 startup fail-fast, refresh full replacement, redaction,
cancellation cleanup, native failure identity를 검증합니다. application은
credential-free 사용 안내만 출력합니다.

## 검증

```bash
./gradlew :aws-settings-boundary:compileKotlin
./gradlew :aws-settings-boundary:compileTestKotlin
./gradlew :aws-settings-boundary:test --tests '*SettingsBoundaryTest'
```
