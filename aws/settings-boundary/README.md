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

## Spring Boot AWS AppConfig Data

`SettingsBoundarySpringApplication` is a Spring Boot 4 consumer example for
the ConfigData URI
`aws-app-config:application#profile#environment`. The default
`application.yml` disables AppConfig auto-configuration, so the ordinary run
and smoke/CI paths finish without credentials or remote calls. The import is
enabled only when the explicit `appconfig` profile is selected:

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

Use `optional:` and `fail-fast=false` only for non-security values such as
feature flags that may be absent at startup. Security settings should keep
`fail-fast=true` and explicit validation. `prefix=appconfig` flattens remote
keys such as `spring.*` and `management.*` below `appconfig.*`, preventing
operational properties from being overwritten. An endpoint override must be a
trusted HTTPS host constrained by a deployment allow-list; never inject an
endpoint or credential from remote AppConfig data, environment data, or
unreviewed command-line input.

For pre-ConfigData inputs, the guard resolves
`bluetape4k.aws.app-config.endpoint-override` first and then the shared
`bluetape4k.aws.endpoint-override`, matching the upstream client fallback. It
validates that effective endpoint only when AppConfig is enabled. When AppConfig
is disabled, the shared override remains available to other AWS examples and
this AppConfig guard stays inactive.

Live AWS access needs `appconfig:StartConfigurationSession` and
`appconfig:GetLatestConfiguration`. Polling creates provider traffic and may
incur charges, so it runs for the context lifetime only when `refresh-interval`
is configured. Production SDK clients use a 10-second API timeout and a
5-second attempt timeout; the 500ms timeout is test-only for the loopback fake.
Real credentials and endpoints stay out of the repository and are supplied
only by explicit production composition code.

Reload behavior depends on the caller:

| Caller | After AppConfig reload |
| --- | --- |
| `Environment#getProperty` | Latest AppConfig values, replaced atomically |
| `@Value` field | Initial binding only |
| `@ConfigurationProperties` bean | Initial binding only; no automatic rebind |

Spring Cloud Context refresh/rebinding is intentionally out of scope. Empty or
malformed payload last-good retention, transport failure followed by a new
session, duplicate-source scheduler ownership, and atomic map replacement are
owned by the lifecycle tests in upstream
[AppConfig PR #537](https://github.com/bluetape4k/bluetape4k-aws/pull/537). This
consumer verifies successful initial load/first update and bounded context/fake
shutdown behavior. On transport failure, the upstream lifecycle retries with
full-jitter backoff capped at five minutes per delay; the retry count is
unbounded, so operators must still control timeout, shutdown, and provider
traffic.

This module does not pin a bluetape4k module version. The root
`bluetape4k-dependencies` BOM is the stable `2.0.0` authority; its published AWS
BOM resolves the AWS Spring Boot coordinate to `1.0.0`. The AWS SDK `appconfigdata` alias is versionless under the AWS
SDK BOM.

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
live AWS or an emulator. The sample's highest-precedence endpoint guard checks
values supplied by command-line, system, or environment property sources before
ConfigData resolution. It rejects non-loopback HTTP, non-regional HTTPS, URI user
info, query, and fragment; the loopback exception exists only for the
credential-isolated fake. Values originating in application/profile ConfigData
must still be constrained by deployment policy; checked-in profiles contain no
endpoint override. Do not resolve credentials from the sample application or put
secret payloads, credentials, endpoints, or raw SDK responses in logs, metrics,
test reports, or error responses. Live AWS execution is outside the default
smoke and CI path and may incur provider charges.

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
