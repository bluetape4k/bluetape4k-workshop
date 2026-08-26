# #558 Multi-broker Kafka Failover Reference 설계 리뷰

## 검토 범위

- 대상: `docs/superpowers/specs/2026-08-26-issue-558-kafka-multi-broker-failover-design.md`
- 기준: 설계 승인 전 6관점 검토(성능, 안정성, 보안, 운영, 개발자/API, 사용자/학습자)와 main 통합 검토
- 로컬 근거: 기존 `messaging/kafka`, #555 usage-billing fixture, Testcontainers 2.0.5 source, `Examples.yml`, `nightly.yml`, `scripts/smoke-validate.sh`
- 외부 근거: [Testcontainers Kafka module](https://java.testcontainers.org/modules/kafka/), [Apache Kafka design](https://kafka.apache.org/41/design/design/)

## 초기 검토 결과와 반영

| 관점 | 초기 판정 | 반영한 결정 |
| --- | --- | --- |
| 성능 | P0=0, P1=2, P2=2 | 고정 workload, prefix ack, 동시 broker 기동, phase/module deadline, client retry와 backoff를 수치화했다. `deepStart` future도 남은 deadline으로 제한한다. |
| 안정성 | P0=0, P1=5, P2=3 | 유효한 listener URI, topic 선생성, 독립 wait strategy, consumer offset/assignment barrier, data-leader와 coordinator 시나리오 분리, replacement/rejoin 순서를 고정했다. |
| 보안 | P0=0, P1=3, P2=4 | String codec와 type header 금지, HTTP/Actuator 비노출, GenericContainer 자동 fallback 금지, local-only/host-network 경계, redacted evidence와 canary 검사를 명시했다. image digest는 구현 전 승인값으로 고정한다. |
| 운영 | P0=0, P1=2, P2=2 | 고정 JSONL schema와 report 경로, `if: always()` artifact, phase별 실패 진단, partial-start rollback, 비파괴 잔여 자원 조회를 명시했다. |
| 개발자/API | P0=0, P1=2, P2=3 | canonical package/main class, test-only Testcontainers scope, 명시 partition/key와 codec, `AckMode.MANUAL`, 재사용 가능한 evidence 필드를 고정했다. |
| 사용자/학습자 | P0=0, P1=2, P2=4 | first-run 명령·Docker/Colima prerequisite·`bootRun` 경계, #555/#558/#559 경계표, locale parity 및 expected evidence를 요구한다. |

## 통합 판정

초기 검토의 P1은 모두 설계에 반영했다. 성능 재검토에서 확인된 `Startables.deepStart` future의 무제한 join 문제도 `get(remaining, MILLISECONDS)`/`orTimeout`과 취소·rollback 계약으로 보완했다. 안정성 재검토에서 확인된 topic 순서, coordinator suffix record, deadline 산술 문제도 동일하게 보완했다. 보안 재검토는 P1=0으로 통과했다.

통합 상태는 **P0=0, P1=0, P2=잔여 위험을 구현/계획 게이트로 이관**이다. 실제 3-broker image startup, 승인 digest, Docker host bind의 local-only 여부, CI artifact canary 검사는 구현 단계의 실행 증거 없이는 완료로 주장하지 않는다.

## 구현 전 보류 결정

1. `apache/kafka:4.2.0`의 startup과 `RepoDigests`를 구현 시작 시 확인하고 승인 digest로 고정한다. tag가 없거나 digest가 다르면 자동 fallback하지 않고 설계를 갱신한다.
2. Docker runtime이 host bind를 local-only로 입증하지 못하면 테스트를 실패시키고, 평문 listener를 production 경로로 설명하지 않는다.
3. `evidence.jsonl`, redacted broker summary, JUnit XML, CI artifact에 synthetic payload/endpoint/credential canary가 남지 않는지 fail-closed 검사한다.
4. README와 diagram은 Testcontainers stop 이후 replacement container가 재가입하는 의미로 통일하고, #559는 fixture API가 아니라 외부 동작/evidence만 소비한다.

## 근거와 한계

- 모든 검토 레인은 read-only였고 source, spec, workflow, Testcontainers source를 대조했다.
- 실제 Docker/Testcontainers 실행은 구현·TDD 단계의 별도 검증 게이트로 남아 있다.
- 설계 문서의 `## 수용 기준`, `## 문서와 시각 자료`, `## DoD`가 구현 plan과 PR body의 추적 기준이다.
