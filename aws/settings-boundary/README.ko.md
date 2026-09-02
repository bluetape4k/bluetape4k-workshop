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

## Spring Boot AWS AppConfig Data

`SettingsBoundarySpringApplication`은 Spring Boot 4 ConfigData URI
`aws-app-config:application#profile#environment`를 사용하는 소비자 예제입니다.
기본 `application.yml`은 AppConfig 자동 구성을 끄므로 일반 실행과 smoke/CI는
credential과 원격 호출 없이 끝납니다. 명시적으로 `appconfig` profile을 켠
경우에만 다음 import가 활성화됩니다.

```yaml
spring:
  config:
    import: optional:aws-app-config:application#profile#environment?format=properties&prefix=appconfig
bluetape4k:
  aws:
    app-config:
      enabled: true
      fail-fast: false
      # refresh-interval: 15s
```

```bash
./gradlew :aws-settings-boundary:test
./gradlew :aws-settings-boundary:bootRun --args='--spring.profiles.active=appconfig'
```

`optional:`과 `fail-fast=false`는 보안에 민감하지 않은 feature flag처럼
원격 source가 없어도 시작할 수 있는 값에만 사용합니다. 보안 설정은
`fail-fast=true`와 명시적인 검증을 유지해야 합니다. `prefix=appconfig`은
원격의 `spring.*`, `management.*` 같은 key를 `appconfig.*` 아래로 평탄화해
운영 property를 덮어쓰지 못하게 합니다. endpoint override는 신뢰할 수 있는
HTTPS host만 배포 allow-list로 허용해야 하며, 원격 AppConfig 데이터·환경변수·
검토하지 않은 command line에서 endpoint나 credential을 주입하지 않습니다.

ConfigData 이전에 보이는 입력에서 guard는 upstream client fallback과 같은
순서로 `bluetape4k.aws.app-config.endpoint-override`를 먼저 보고 공통
`bluetape4k.aws.endpoint-override`를 fallback으로 사용합니다. AppConfig가
활성화된 경우에만 effective endpoint를 검증하며, AppConfig가 비활성화되면
공통 override는 다른 AWS 예제에서 사용할 수 있고 이 AppConfig guard는
동작하지 않습니다.

실제 AWS에는 `appconfig:StartConfigurationSession`과
`appconfig:GetLatestConfiguration` 권한이 필요합니다. polling은 provider
traffic과 비용을 만들 수 있으므로 `refresh-interval`을 명시했을 때만
context 수명 동안 동작합니다. 운영 SDK client에는 API timeout 10초와
attempt timeout 5초를 적용하고, 테스트의 loopback fake에서만 500ms timeout을
사용합니다. 실제 credential과 endpoint는 저장소에 넣지 않고 명시적인 운영
조합 코드에서만 전달합니다.

runtime reload 결과는 caller 종류에 따라 다릅니다.

| Caller | AppConfig reload 후 |
| --- | --- |
| `Environment#getProperty` | 최신 AppConfig 값으로 원자적 교체 |
| `@Value` field | 초기 binding 값만 유지 |
| `@ConfigurationProperties` bean | 초기 binding 값만 유지하며 자동 rebind하지 않음 |

Spring Cloud Context refresh/rebinding은 이 예제의 범위가 아닙니다. 빈
payload와 malformed payload의 last-good 보존, transport failure 뒤 새
session 재시도, 중복 source scheduler와 atomic map 교체는 upstream
[AppConfig PR #537](https://github.com/bluetape4k/bluetape4k-aws/pull/537)의
lifecycle 테스트가 소유하며, 이 consumer는 성공적인 초기 로드·첫 갱신과
context/fake 종료 경계를 검증합니다. transport failure가 발생하면 upstream
lifecycle은 delay당 최대 5분의 full-jitter backoff로 재시도하며 횟수는
무제한입니다. 따라서 운영자는 timeout, shutdown과 provider traffic을 함께
제어해야 합니다.

bluetape4k module version은 이 모듈에서 직접 지정하지 않습니다. root
`bluetape4k-dependencies` BOM이 안정 `2.0.0` authority이며, 공개된 AWS BOM은
AWS Spring Boot 좌표를 `1.0.0`으로 해석합니다.
AWS SDK `appconfigdata`는 AWS SDK BOM에서 versionless alias로 해석합니다.

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
literal-loopback endpoint를 전달합니다. sample의 highest-precedence endpoint
guard는 command line·system·environment property source에서 온 값을
ConfigData 해석 전에 검사합니다. loopback 외 HTTP, region별 AWS가 아닌
HTTPS, URI user info·query·fragment를 거부하며 loopback 예외는
credential-isolated fake 전용입니다. application/profile ConfigData에서 온
endpoint는 배포 정책으로 별도 제한해야 하고 저장소 profile에는 endpoint
override가 없습니다. sample application에서 credential을 해석하지 않으며
secret payload, credential, endpoint, raw SDK response를 log, metric, test
report, error response에 넣지 않습니다. 실제 AWS 실행은 기본 smoke와 CI
경로 밖에 있으며 provider 비용이 발생할 수 있습니다.

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
