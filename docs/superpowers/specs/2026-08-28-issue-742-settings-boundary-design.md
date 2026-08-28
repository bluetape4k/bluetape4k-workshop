# #742 AWS settings boundary 설계

## 문제와 목표

AWS 0.5.0에서 제공하는 Secrets Manager와 Parameter Store wrapper를
워크숍의 설정 소비자 경계로 연결한다. 새 `aws/settings-boundary` 모듈은
특정 provider의 예외·secret 표현·refresh 동작을 애플리케이션 전체로
누출하지 않으면서 다음 계약을 실행 가능한 코드로 보여준다.

- Secrets Manager와 Parameter Store를 같은 provider-neutral `SettingsSource`
  계약으로 소비한다.
- startup lookup과 refresh를 별도 연산으로 표현한다.
- 누락과 권한 오류를 `Missing`/`Denied` 결과로 분류하고, 정책에 따라
  fail-fast 또는 omit fallback을 선택한다.
- refresh는 이전 snapshot을 병합하지 않는 full replacement라서 삭제·누락된
  값이 잘못 재사용되지 않는다.
- secret payload는 `AwsSecretValue`로 감싸고 로그·예외·metric tag에
  노출하지 않는다.
- 기본 실행과 테스트는 fake source/client로 credential-free를 유지한다.

범위는 consumer 예제다. upstream AWS SDK와 bluetape4k wrapper의 retry,
cache, Spring property source 자동 등록 또는 provider별 보장을 새로
추상화하지 않는다.

## 근거와 제약

- 대상 이슈: [#742](https://github.com/bluetape4k/bluetape4k-workshop/issues/742)
- upstream AWS Kotlin wrapper: `AwsSecretValue`,
  `SecretsManagerClient.getSecretString`, `SsmClient.getSecureParameter`.
- AWS Kotlin service alias는 현재 catalog의 `aws-kotlin` 호환성 라인을
  따른다. workshop consumer는 bluetape4k dependency BOM 규칙을 지킨다.
- 실제 AWS credential/network 호출은 기본 실행·CI에 포함하지 않는다.
- 이 branch는 미병합 #741 child 위에 쌓이므로 manifest의
  `stacked-parent-head` scope로 정확한 base/head를 기록한다.

## 선택한 접근

### Provider-neutral contract

```kotlin
interface SettingsSource {
    suspend fun resolve(key: String): SettingsResolution
}

sealed interface SettingsResolution {
    data class Found(val value: AwsSecretValue) : SettingsResolution
    data object Missing : SettingsResolution
    data object Denied : SettingsResolution
}
```

`SecretsManagerSettingsSource`와 `ParameterStoreSettingsSource`는 각 SDK
client factory를 operation scope에서 `useSafe`로 닫고, upstream helper가
반환한 secret wrapper를 그대로 `Found`로 전달한다. `ResourceNotFound`,
`ParameterNotFound`, access-denied error code는 공통 결과로 분류하고,
취소·미분류 오류는 원본 identity를 유지해 다시 던진다.

### Snapshot과 fallback

`SettingsResolver`는 여러 key를 순서대로 조회해 `SettingsSnapshot`을 만든다.
`startup(keys)`와 `refresh(keys)` 모두 새 snapshot을 만든다. 각 단계에는
`SettingsFallbackPolicy`를 지정할 수 있다.

- `FAIL_FAST`: Missing 또는 Denied를 만나는 즉시 provider payload 없이
  `SettingsUnavailableException`을 던진다.
- `OMIT`: 해당 결과를 snapshot에 남기고 Found 값만 소비한다. 이전 snapshot을
  인자로 받지 않으므로 refresh에서 이전 secret을 재사용하지 않는다.

snapshot의 `redactedEntries()`는 모든 Found 값을 `AwsSecretValue.toString()`
의 redacted 표현으로만 반환한다. 실제 값은 `reveal()`을 호출하는 명시적인
애플리케이션 경계 밖으로 전달하지 않는다.

### 대안 검토

1. **upstream Spring EnvironmentPostProcessor 직접 사용** — provider별
   property source 설정과 fail-fast 규칙은 제공하지만, 두 provider를 같은
   consumer contract와 credential-free fake로 비교하기 어렵다. 이번 모듈의
   핵심 학습 목표인 provider-neutral resolver에는 맞지 않아 제외한다.
2. **공유 mutable cache에 refresh 결과 병합** — 운영 cache에는 유용할 수
   있으나 삭제된 secret을 stale 값으로 유지할 위험이 있다. refresh는 full
   replacement로 고정한다.
3. **실제 LocalStack/에뮬레이터만 사용** — wire fidelity는 높지만 credential,
   container, startup 비용이 기본 검증을 지배한다. adapter fake와 exception
   classification 단위 테스트를 기본으로 선택한다.

## 실패·취소·보안

- SDK의 `CancellationException`과 분류되지 않은 예외는 broad catch로
  삼키지 않고 원본 그대로 전달한다.
- SDK client는 조회마다 factory에서 만들고 `useSafe` 범위에서 닫는다.
- 오류 메시지에는 key의 존재를 설명할 수 있지만 secret payload, credential,
  endpoint credential, resolved value를 넣지 않는다.
- `SettingsSnapshot.toString()`과 redacted view는 `AwsSecretValue`의 안전한
  표현을 사용한다. metric tag와 HTTP response에 값을 복사하는 예제는
  제공하지 않는다.

## 검증 계획

- 각 provider의 성공·누락·권한 오류를 fake client로 검증한다.
- startup `FAIL_FAST`와 refresh `OMIT` 결과를 확인한다.
- 첫 refresh에서 `old-secret`, 다음 refresh에서 Missing을 반환하는 fake로
  이전 값이 snapshot에 남지 않음을 검증한다.
- key/model 이름과 wrapper exception은 확인하되 실제 credential/network는
  호출하지 않는다.
- module test/build, `projects`, AWS smoke/stale-check, checker exact scope,
  bilingual README parity와 Korean terminology audit를 실행한다.

## 완료 조건

- [ ] Secrets Manager와 Parameter Store의 Found/Missing/Denied 계약 및
      credential-free 테스트가 있다.
- [ ] startup/refresh fallback과 stale secret 비재사용 테스트가 있다.
- [ ] `AwsSecretValue` redaction과 payload 비노출 문서가 한·영 README에
      일치한다.
- [ ] catalog alias, module registry, smoke/full workflow, validation matrix가
      등록된다.
- [ ] stacked child manifest scope와 fresh coordinator receipt가 정확하다.
