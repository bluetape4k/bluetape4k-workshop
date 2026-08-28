# #742 AWS settings boundary 구현 계획

> 이 계획은 #741 Bedrock child branch 위에 쌓이는 stacked PR train의 두 번째
> child를 위한 실행 기록이다. 구현·테스트·문서는 모두 한국어 reader-facing
> 계약과 Kotlin-native 패턴을 따른다.

## 목표

`aws/settings-boundary`에서 Secrets Manager와 Parameter Store를 동일한
provider-neutral source로 소비하고, startup/refresh 및 missing/denied
fallback과 redaction을 credential-free 테스트로 증명한다.

## 작업 순서

### 1. 모듈 및 catalog 골격

- [x] `aws/settings-boundary/build.gradle.kts`와 application entrypoint 추가
- [x] `aws-kotlin-secretsmanager`, `aws-kotlin-ssm` alias 추가
- [x] module registration, test resources, local-only 실행 확인
- [x] RED 상태를 확인하는 등록 테스트 실행

### 2. Contract-first source/resolver 테스트

- [x] `SettingsSource`, `SettingsResolution`, `SettingsFallbackPolicy`,
      `SettingsSnapshot` public 계약 테스트 작성
- [x] Secrets Manager 및 Parameter Store fake client 성공·누락·권한 오류
      테스트 작성
- [x] startup fail-fast, refresh omit, stale secret 비재사용 테스트 작성
- [x] redaction view에서 secret payload가 노출되지 않는지 테스트 작성

### 3. 최소 Kotlin 구현

- [x] upstream `getSecretString`/`getSecureParameter`와 `useSafe` 재사용
- [x] cancellation·미분류 오류 identity 보존
- [x] operation-owned client lifecycle과 full-replacement snapshot 구현
- [x] 테스트를 GREEN으로 전환

### 4. 문서·검증 등록

- [x] 모듈 한·영 README와 AWS root README 갱신
- [x] `scripts/smoke-validate.sh`, `.github/workflows/Examples.yml`,
      `docs/coverage-matrix.md` 등록
- [x] Korean terminology audit, `projects`, module test/build, smoke/stale-check
      실행
- [x] Korean lesson과 7-Tier review artifact 작성

### 5. stacked scope 및 PR publication

- [x] `docs/ecosystem-reuse-train.json`에 정확한 #742 child scope 추가
- [ ] coordinator receipt checksum 재계산 및 exact base/head 검사
- [ ] #741 head를 base로 PR 생성, assignee/milestone/labels/body/DoD read-back
- [ ] terminal #776 이전까지 merge하지 않고 PENDING 상태 유지

## 검증 명령

```bash
./gradlew :aws-settings-boundary:test --no-build-cache --console=plain
./gradlew :aws-settings-boundary:build --no-build-cache --console=plain
./gradlew projects --console=plain
./scripts/smoke-validate.sh aws
./scripts/smoke-validate.sh stale-check
python3 -m unittest discover -s .github/scripts -p 'test_check_ecosystem_reuse.py'
git diff --check
```

## 위험과 중단 조건

- AWS SDK wrapper API가 catalog/BOM에서 해석되지 않으면 upstream source와
  Gradle dependency report를 재확인하고 raw SDK 호출을 중복 구현하지 않는다.
- 실제 credential/network가 기본 테스트에서 필요해지면 구현을 중단하고
  fake factory 경계를 복구한다.
- checker가 stacked child를 고정 track으로 오인하면 inventory를 늘리지 말고
  `stacked-parent-head` scope와 receipt를 먼저 점검한다.
