# Issue #317 Implementation Review

- 날짜: 2026-06-30
- 이슈: https://github.com/bluetape4k/bluetape4k-workshop/issues/317
- Spec: `docs/superpowers/specs/2026-06-30-issue-317-cloudwatch-imds-observability-design.md`
- Plan: `docs/superpowers/plans/2026-06-30-issue-317-cloudwatch-imds-observability-plan.md`
- 리뷰 게이트: Step 6-R
- 실행 메모: 이 세션에서 native subagent lifecycle이 불안정했으므로, main session이 동일한 6개 관점 implementation review를 수행하고 그 증거를 여기에 기록했다.

## 판정

| 심각도 | 건수 | 상태 |
|---|---:|---|
| P0 | 0 | PASS |
| P1 | 0 | PASS |
| P2 | 0 | PASS |
| P3 | 2 | 선택적 follow-up |

P0=0, P1=0이므로 Step 6-R은 진행할 수 있다.

## 관점별 발견사항

### 1. 성능

- P0/P1: 없음.
- 근거: CloudWatch dimension은 `Outcome`, `Service`, `Source`로 제한되어 있으며, `eventId` 같은 high-cardinality 값은 metric dimension으로 사용되지 않는다.
- 근거: 기본 테스트는 container나 real AWS endpoint 없이 실행되므로, module은 smoke coverage 대상에 적합하다.

### 2. 안정성

- P0/P1: 없음.
- 근거: Metric, log, meter, metadata publish state는 `PublishStatus` / `MetadataSnapshot`을 통해 독립적으로 보고되므로, 단일 publisher 실패가 다른 성공을 숨기지 않는다.
- 근거: suspend publish 및 metadata path에서 `CancellationException`을 다시 throw한다.
- 근거: `./gradlew :aws-cloudwatch-imds-observability:compileKotlin
  :aws-cloudwatch-imds-observability:compileTestKotlin
  :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain`
  명령은 `rc=0`, `BUILD SUCCESSFUL in 4s`를 반환했다.

### 3. 보안

- P0/P1: 없음.
- 근거: Log JSON은 workshop field만 사용하며 token, secret, password, credential, authorization, bearer-like pattern을 redact한다.
- 근거: IMDS read는 explicit opt-in이며 instance id, region, availability zone으로 제한된다. 테스트는 credential document path를 읽지 않는다고 assert한다.
- 근거: 기본 `application.yml`은 bluetape4k AWS, CloudWatch, CloudWatch Logs, IMDS auto-configuration을 비활성화한다.

### 4. 운영자

- P0/P1: 없음.
- 근거: `.github/workflows/Examples.yml`에는 이제 module path filter, smoke Gradle task, test artifact path가 포함되어 있다.
- 근거: `scripts/smoke-validate.sh stale-check`는 active modules `89 (expected: 89)`, stale refs `0`, broken image links `0`을 보고했다.
- 근거: `actionlint .github/workflows/Examples.yml`은 성공을 반환했다.

### 5. 개발자/API

- P0/P1: 없음.
- 근거: module은 AWS SDK CloudWatch, CloudWatch Logs, IMDS artifact에 catalog alias를 사용하며 local bluetape4k version을 pin하지 않는다.
- 근거: module build file에서 `springBoot.mainClass`가 명시되어 있다.
- 근거: `./gradlew projects --console=plain`은 `rc=0`, `project_count=89`, `new_project_present=yes`를 반환했다.

### 6. 사용자/호출자

- P0/P1: 없음.
- 근거: `README.md`와 `README.ko.md`는 source-equivalent이며 local run command, telemetry request example, metadata skipped output, metadata opt-in output, normalized validation failure output, optional real AWS profile command, IMDS boundary wording, test coverage를 포함한다.
- 근거: Diagram은 SVG+PNG로 생성되어 README에 embedded되어 있다. Architecture diagram은 top-to-bottom 구조, layer separation을 갖추고 있으며 CloudWatch managed-service card에는 official `aws.cloudwatch` catalog icon을 사용한다.
- 근거: Sequence diagram은 numbered call, participant role, lifeline, activation bar, transparent `alt` body, branch-specific line color, full-size visual inspection을 사용한다.

## Diagram Verification

수정된 diagram:

- `docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.svg`
- `docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-architecture-01.png`
- `docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.svg`
- `docs/images/readme-diagrams/aws-cloudwatch-imds-observability-readme-sequence-01.png`

근거:

- `diagram-sequence-style-audit.py`: PASS, `sequence_files=1`.
- `diagram-geometry-audit.py`: PASS, `geometry_failures=0` for both files.
- `diagram-endpoint-audit.py`: PASS, `files=2`.
- `diagram-mixed-corner-audit.py`: PASS, `files=2`, `paths=20`, `q_bends=0`, `failures=0`.
- `diagram-connector-audit.py`: PASS; architecture `markers=2`, `connectors=6`, `cards=15`, `intrusions=0`, `crossings=0`; sequence `markers=2`, `connectors=6`, `cards=0`, `intrusions=0`, `crossings=0`.
- `node scripts/validate-readme-architecture-diagrams.mjs`: PASS, `checked=102`, `legacySkipped=92`, `failures=0`.
- `node scripts/validate-sequence-diagrams.mjs`: PASS, `checked=77`, `legacySkipped=62`, `failures=0`.
- Full-size PNG visual inspection: text-fit 수정 후 생성된 두 PNG 파일 모두 PASS.

Icon 근거:

- `aws.cloudwatch`: official AWS Architecture Icon
  `docs/icons/aws/architecture-icons-2026-04-30/Architecture-Service-Icons_04302026/Arch_Management-Tools/48/Arch_Amazon-CloudWatch_48.svg`.
- `spring.boot`: catalog icon `docs/icons/spring/spring-boot.svg`.
- `observability.micrometer`: catalog icon `docs/icons/observability/micrometer.svg`.

## Validation Evidence

- `node scripts/validate-readme-language.mjs`: PASS, offenders `0`.
- `node scripts/validate-readme-parity.mjs`: PASS, failures `0`.
- `node scripts/validate-readme-architecture-diagrams.mjs`: PASS.
- `node scripts/validate-sequence-diagrams.mjs`: PASS.
- `actionlint .github/workflows/Examples.yml`: PASS.
- `git diff --check`: PASS.
- `./scripts/smoke-validate.sh stale-check`: PASS, modules `89/89`, stale refs `0`, broken image links `0`.
- `./gradlew :aws-cloudwatch-imds-observability:compileKotlin
  :aws-cloudwatch-imds-observability:compileTestKotlin
  :aws-cloudwatch-imds-observability:test --warning-mode all --console=plain`:
  PASS.
- `./gradlew projects --console=plain`: PASS, project count `89`, new project present.

## 메모와 잔여 위험

- P3: Optional real AWS profile command는 문서화했지만 실행하지 않았다. issue가 기본 검증에서 real AWS resource를 요구하지 않으므로 의도적인 범위 제한이다.
- P3: `scripts/validate-sequence-diagrams.mjs`는 pre-existing `spring-boot-text-moderation-api-readme-sequence-01.svg`를 legacy로 표시한다. 이 파일은 현재 invisible-message-label validator contract보다 먼저 작성되었기 때문이다.

## 열린 질문

없음.
