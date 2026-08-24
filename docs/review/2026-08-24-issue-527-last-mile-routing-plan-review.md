# Issue #527 Last-Mile Routing 계획 검토

- 검토일: 2026-08-24
- 대상 계획: `docs/superpowers/plans/2026-08-24-issue-527-last-mile-routing.md`
- 기준 설계: `docs/superpowers/specs/2026-08-24-issue-527-last-mile-routing-design.md`
- 검토 방식: Performance, Stability, Security, Operator/Ops, Developer/API,
  User/caller 여섯 관점과 main integration
- 판정: P0/P1 없음, 계획 review PASS

## 검토 범위와 근거

계획의 Task 순서가 설계의 불변식과 경계를 보존하는지, 각 위험이 RED/GREEN
테스트와 실행 명령에 연결되는지 확인했다. 기준은 live Issue #527, 현재
`optimization/field-service-dispatch`의 실제 파일·Gradle·테스트, #524의
internal 경계, GNO research note, Timefold 공식 integration/API 문서다.

## 독립 관점 결과

| 관점 | 결과 | 계획 근거와 처분 |
|---|---|---|
| Performance | P0=0, P1=0, P2=1 | Task 3 O(1) matrix/max envelope, Task 4 keyset/index, Task 9 receipt를 확인했다. benchmark 수치는 report-only로 두며 실제 실행에서 채운다. |
| Stability | P0=0, P1=0, P2=1 | Task 5 bounded retry/lease/replay, Task 6 coalescing/reconnect/CAS, Task 9 no-skip Testcontainers를 확인했다. 실제 restart 증거는 구현 후 수집한다. |
| Security | P0=0, P1=0, P2=1 | Task 7 CSP/textContent/redaction과 Task 9 observability scan이 raw payload/secret 누출을 차단한다. production auth는 명시적 비목표다. |
| Operator/Ops | P0=0, P1=0, P2=1 | Task 5 outbox fencing/dead-letter, Task 6 lifecycle, Task 9 health/metrics와 detekt fallback을 확인했다. 운영 migration은 추가하지 않는다. |
| Developer/API | P0=0, P1=0, P2=0 | exact file map, no #524 project dependency, BOM-only, endpoint/test names, workflow registration이 있다. `settings.gradle.kts` 자동 등록은 Task 1 projects로 검증한다. |
| User/caller | P0=0, P1=0, P2=1 | deterministic fixture, explicit failures, browser redacted projection, README usage와 unsupported production scope가 Task 3/7/8에 연결된다. |

## Main integration

| 우선순위 | 질문 | 판정 | 근거 |
|---|---|---|---|
| P0 | planner 결과가 PostgreSQL authority를 우회하는가? | PASS | Task 4 proposal/committed 분리와 Task 6 approval transaction 재검증 |
| P0 | stale/duplicate/out-of-order callback이 쓰기를 오염시키는가? | PASS | Task 2 canonical digest와 Task 5 inbox unique/stale audit |
| P0 | started stop과 carrier version이 자동으로 이동하는가? | PASS | Task 3 pin constraint와 Task 6 reconnect/approval CAS |
| P1 | 실제 provider가 없어도 모든 핵심 경로가 재현되는가? | PASS | Task 3 fixed matrix + Task 5 deterministic provider + Task 9 fixtures |
| P1 | 오류·redaction·browser 안전성이 실행 가능한가? | PASS | Task 7 controller/browser tests와 Task 9 smoke/scan |
| P1 | 모듈 추가가 repository policy와 충돌하지 않는가? | PASS | Task 1 BOM/projects, Task 8 README/workflow/stale, root detekt policy |
| P2 | 실제 Timefold/OSRM·GPS·인증까지 검증하는가? | 보류 | Issue non-goal; 후속 provider/production scope에서 별도 설계 |

## 3R 계획 점검

| 점검 | 결과 | 증거 |
|---|---|---|
| Requirements | PASS | Issue의 pickup/delivery/capacity/window/skill/pin/event/failure 요구가 Task 2–8과 추적성 표에 있다. |
| Risks | PASS | provider outage, matrix miss, stale CAS, duplicate digest, reconnect, burst, XSS/redaction, lifecycle가 각 테스트와 bounded 처분을 가진다. |
| Run order | PASS | Task 0 gate → module → domain → planner → persistence → provider → lifecycle → HTTP → ecosystem → verification 순서가 선형이고 중단 지점이 명시됐다. |

## 승인 조건과 남은 범위

- 계획 P0/P1은 0이다. 계획 Task 1 구현을 시작할 수 있는 품질 gate는 충족했다.
- 구현 중 module boundary, provider wire contract, schema authority가 바뀌면 이
  review로 되돌아와 해당 관점을 재실행한다.
- 실제 provider credential/API, production auth/CSRF, GPS, geocoding, traffic,
  production migration, live carrier contract는 계획의 PASS 대상이 아니다.
- 이 문서는 PR 생성·push·merge 승인이나 Epic 종료를 의미하지 않는다.

## Traceability와 writer gate

- 설계의 seven acceptance groups가 계획의 Task 2–9와 주 검증 명령에 매핑되어 있다.
- `SPW-01`: 대상 독자·언어·Issue/설계/저장소 근거와 범위를 기록했다.
- `SPW-02`: six-lane finding, main integration, P0/P1 종료 조건, P2 보류를 기록했다.
- `SPW-03`: 한국어 기술 문체와 API/command/URL token 보존을 확인한다.
- `SPW-04`: 현재 #524/#525 파일과 Gradle/detekt 정책, 공식 문서 경계를 대조했다.
- `SPW-05`: plan read-back, fenced block parity, placeholder scan, terminology audit,
  `git diff --check`를 실행한다.

## 문서 작성 게이트

| 항목 | 상태 | 증거 |
|---|---|---|
| SPW-01 독자·목적·출처·범위 | PASS | 계획/설계/Issue/live repository/GNO/공식 문서 |
| SPW-02 선택지·경계·실패·수용 기준 | PASS | six lanes, main integration, 3R, Task/추적성 |
| SPW-03 한국어 기술 문체·용어 | PASS | Korean writer checklist와 보존 토큰 |
| SPW-04 현재 소스·외부 계약 대조 | PASS | #524/#525 read-back 및 Timefold API 경계 |
| SPW-05 read-back·Markdown·공백 | PASS | 전체 read-back, audit, `git diff --check` |

## 최종 판정

`DONE (계획 review 단계)`: P0/P1=0이며 구현 Task 1로 진행할 수 있다. 다음
작업은 이 계획에 따라 #527 모듈을 TDD로 구현하고, 구현 review/lesson/검증
receipt를 새로 수집하는 것이다. PR/merge/Epic 종료는 별도 gate다.
