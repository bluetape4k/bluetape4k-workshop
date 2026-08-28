# Issue #742 AWS settings boundary

## Context

AWS 0.5.0의 Secrets Manager와 Parameter Store wrapper를 소비하는
워크숍 예제가 없었다. 실제 credential과 network를 기본 테스트에 넣으면
설정 누출과 비용 위험이 생기므로 두 provider를 하나의 작은 settings
consumer 경계로 비교할 필요가 있었다.

## Decision or Finding

- `aws/settings-boundary/`를 독립 application consumer로 추가했다.
- `SettingsSource`는 `Found`, `Missing`, `Denied`만 노출하고 provider별
  exception을 숨긴다.
- Secrets Manager는 upstream `getSecretString`, Parameter Store는 upstream
  `getSecureParameter`를 사용하며 각 lookup이 client를 `useSafe`로 소유한다.
- startup은 기본 `FAIL`, refresh는 기본 `OMIT` 정책을 사용한다. refresh는
  이전 설정 결과를 병합하지 않는 full replacement다.
- `AwsSecretValue`와 redacted view를 사용하고 payload를 log, metric tag,
  error response, test report에 넣지 않는다.
- 미분류 exception과 cancellation은 원본 identity를 보존한다.

## Outcome

두 provider 모두 성공·누락·권한 오류를 credential-free fake loader로
검증한다. 첫 설정 결과의 secret이 다음 refresh에서 Missing으로 바뀔 때
이전 값이 재사용되지 않으며, startup fail-fast와 omit fallback을 동시에
설명한다. module README와 AWS root README는 같은 실행·보안 계약을
기록하고 smoke/full/coverage 등록을 완료했다.

## Verification

- `:aws-settings-boundary:test --tests '*SettingsBoundaryTest'`: 9개 통과
- `:aws-settings-boundary:build`: 통과
- `./gradlew projects`: `:aws-settings-boundary` 자동 등록 확인
- AWS smoke/stale-check와 Korean terminology audit: 통과
- 실제 AWS credential, endpoint, network 호출: 없음

## Future Guidance

새 설정 provider를 추가할 때는 `SettingsSource` 결과 계약을 유지하고,
provider exception을 boundary 밖으로 내보내지 않는다. refresh는 full
replacement를 유지하며 stale secret fallback을 추가하지 않는다. 실제 AWS
또는 emulator 경로가 필요하면 명시적 factory와 loopback/HTTPS endpoint,
IAM scope, cleanup 조건을 별도 문서와 opt-in 명령으로 남긴다.
