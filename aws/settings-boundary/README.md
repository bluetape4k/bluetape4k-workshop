# AWS Settings Boundary Workshop

[한국어](README.ko.md) | English

This example consumes the upstream bluetape4k AWS Kotlin wrappers for Secrets
Manager and Systems Manager Parameter Store behind one provider-neutral
settings boundary. The default sample and tests are credential-free.

## Consumer contract

| API | Contract |
| --- | --- |
| `SettingsSource` | Resolves one key to `Found`, `Missing`, or `Denied` without leaking provider exceptions. |
| `SecretsManagerSettingsSource` | Uses upstream `getSecretString` inside an operation-owned `useSafe` client scope. |
| `ParameterStoreSettingsSource` | Uses upstream `getSecureParameter` inside an operation-owned `useSafe` client scope. |
| `SettingsResolver.startup` | Builds a new snapshot; strict `FAIL` is the default for missing and denied results. |
| `SettingsResolver.refresh` | Builds a full replacement snapshot; `OMIT` is the default and never merges an old secret. |
| `SettingsSnapshot.redactedEntries` | Returns only redacted markers; `AwsSecretValue` keeps the payload out of logs and reports. |

The source adapters classify provider-specific not-found and access-denied
errors into the shared result type. Cancellation and unclassified failures keep
their original identity. A client is created and closed for each lookup.

The consumer deliberately does not add retry, mutable caching, stale-value
fallback, metric tags containing values, or HTTP response serialization.

```kotlin
implementation(libs.aws.kotlin.secretsmanager)
implementation(libs.aws.kotlin.ssm)
implementation(libs.bluetape4k.aws.kotlin)
```

The wrapper source is maintained in the
[bluetape4k AWS Kotlin module](https://github.com/bluetape4k/bluetape4k-aws/tree/main/aws-kotlin).

## Startup and refresh

```kotlin
val source = SecretsManagerSettingsSource {
    secretsManagerClientOf(region = "ap-northeast-2")
}
val resolver = SettingsResolver(source)

val startup = resolver.startup(setOf("database/password"))
val refreshed = resolver.refresh(setOf("database/password"))
val safeView = refreshed.redactedEntries()
```

`startup` defaults to fail-fast for `Missing` and `Denied`. Configure
`SettingsFallbackPolicy.omit()` when a caller can operate without the key. The
default refresh policy is omit, but it still records the new `Missing`/`Denied`
outcome in the replacement snapshot; it never copies the previous snapshot.

`ParameterStoreSettingsSource` uses the secure-parameter wrapper:

```kotlin
val source = ParameterStoreSettingsSource {
    ssmClientOf(region = "ap-northeast-2")
}
```

Use an explicit factory and a controlled HTTPS or literal-loopback endpoint for
live AWS or an emulator. Do not resolve credentials from the sample application
or put secret payloads, credentials, endpoints, or raw SDK responses in logs,
metrics, test reports, or error responses. Live AWS execution is outside the
default smoke and CI path and may incur provider charges.

## Local verification

```bash
./gradlew :aws-settings-boundary:test
./gradlew :aws-settings-boundary:build
./gradlew :aws-settings-boundary:run
```

The tests use fake source loaders and fake clients. They cover both providers'
success, missing, and denied outcomes; startup fail-fast; refresh full
replacement; redaction; cancellation cleanup; and native failure identity.
The application only prints a credential-free usage hint.

## Verification

```bash
./gradlew :aws-settings-boundary:compileKotlin
./gradlew :aws-settings-boundary:compileTestKotlin
./gradlew :aws-settings-boundary:test --tests '*SettingsBoundaryTest'
```
