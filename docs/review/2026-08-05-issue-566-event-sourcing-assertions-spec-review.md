# Issue #566 설계 6-lens review

## 범위

- 대상: `commerce-usage-metering-billing-event-sourcing`의 21개 Kotlin test 파일 assertion 이관 설계
- 설계 문서: `docs/superpowers/specs/2026-08-05-issue-566-event-sourcing-assertions-design.md`
- 검토 기준 문서 SHA-256: `4c75ea854fbaa07acd4ad61c92fc231cc9901e9070aaf3fe8a76adc107a33ee2`
- 검토는 read-only이며 Kotlin source, production, dependency, workflow, credentials, release surface를 수정하지 않았다.

## 6-lens 결과

| Lens | Verdict | P0 | P1 | P2 | P3 | 핵심 확인 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| caller/user intent | APPROVE | 0 | 0 | 0 | 0 | product diff와 process evidence 분리, PR/dispatch/issue/rollback/merge/release 독립 승인, exact-head 부재 시 N/A/no-dispatch |
| developer/design | APPROVE | 0 | 0 | 0 | 0 | 21/21 manifest, intent-specific matcher mapping, nullable/Java Class narrowing, anchored artifact inventory, exact pair 비교, Bash capture |
| security | APPROVE* | 0 | 0 | 0 | 0 | local report/XML/binary scan, symlink/extra/special-entry fail-closed, fixture 및 two-pass redaction; CI는 post-upload metadata-only 경계 |
| operator/Ops | PASS | 0 | 0 | 0 | 0 | 네 Gradle invocation, `PIPESTATUS` 양쪽 status, temp log trap cleanup, evidence owner/path, exact-head 조건 |
| performance/timing | APPROVE | 0 | 0 | 0 | 0 | combined baseline은 context-only, 동일 split topology의 `B_split`과 `2 × B_split`, invocation별 15분 timeout |
| stability/reliability | APPROVE | 0 | 0 | 0 | 0 | test/integration/stress count gate, mutex/JUnit serialization, fixture lifecycle, rollback/full-proof, residual scan |

`*` security 승인은 기존 workflow의 post-upload artifact 경계를 그대로 인정하는 조건부 결과다. pre-upload secret prevention을 요구하는 변경은 별도 workflow-hardening 범위와 승인으로 분리한다.

## Fresh evidence

- `git diff --check`: PASS
- embedded Python scanner AST parse: PASS
- embedded Bash capture syntax: PASS
- 실제 local Gradle 산출물 inventory: XML `test=19`, `integrationTest=35`, `stressTest=1`; failures/errors/skips는 모두 0
- 실제 report HTML/CSS/JS와 Gradle binary metadata inventory: PASS
- scanner 결과: `matches=12`, allowed placeholder `12`, unexpected `0`
- redaction fixture: 허용 6/6, reject 8/8
- manifest와 현재 assertion 후보 집합: `21/21`, missing `0`, extra `0`
- 설계 단계에서는 `B_split`, 최종 split timing, exact-head CI/Nightly artifact, migration/redaction record가 아직 생성되지 않았다. 해당 구현 evidence가 생기기 전까지 implementation status는 `PENDING`이다.

## 결정

- 설계 6-lens review는 `APPROVED`로 종료한다.
- product implementation은 다음 단계인 plan review와 사용자 plan 승인 이후에만 시작한다.
- PR 생성, workflow dispatch, 후속 issue, rollback/revert, merge, release/tag/publication은 각각 별도 승인 없이는 실행하지 않는다.
- exact implementation `HEAD`와 일치하는 기존 CI artifact가 없으면 dispatch 없이 `N/A`를 기록한다.

## 남은 게이트

1. 사용자 plan 승인 전에는 구현하지 않는다.
2. 구현 전/후 동일 split topology의 `B_split`과 final timing을 수집한다.
3. 구현 후 migration record, lesson, redaction two-pass 결과와 필요한 exact-head CI audit를 기록한다.
4. implementation review, PR, merge 및 release 관련 승인은 각각 해당 시점에 새로 받는다.

상태: `DESIGN APPROVED / IMPLEMENTATION PENDING`
