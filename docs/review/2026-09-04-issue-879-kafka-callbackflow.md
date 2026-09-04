# Issue #879 Kafka producer callbackFlow 구현 리뷰

## 범위

- 대상: `messaging/kafka-reply`의 `KafkaProducerFlow`와 테스트, README/CI/stale-check 등록
- 기준: dependency `2.0.0`, 기존 `ReplyingKafkaTemplate` request-reply 회귀 없음

## 확인 결과

| 검토 항목 | 결과 | 근거 |
|---|---|---|
| callback metadata 성공 경로 | 통과 | fake producer와 Testcontainers Kafka round trip |
| callback failure와 malformed 결과 | 통과 | 첫 원인 및 명시적 malformed failure 테스트 |
| bounded in-flight/backpressure | 통과 | `Semaphore`, bounded channel, callback buffer full 테스트 |
| collector cancellation/late callback | 통과 | pending future 취소와 late callback 무시 테스트 |
| flush/close lifecycle | 통과 | 정상·실패·cleanup suppressed 테스트와 close count 검증 |
| 기존 request-reply 보존 | 통과 | 기존 `PingController`/handler 코드 변경 없음 |
| consumer 문서/운영 등록 | 통과 예정 | root/module README, matrix, workflow, stale-check, lesson 동시 수정 |

## 리스크와 후속 조치

- Kafka producer 구현체가 `Future.cancel(false)`를 실제 전송 중단으로 보장하는지는 provider 문서에 따라 다를 수
  있으므로, 운영 adapter에서는 callback future 취소 의미를 확인한다.
- `flush`와 `close`는 bounded timeout으로 감싸지만, timeout 시 broker가 이미 수락한 record의 최종 상태는
  broker 설정과 producer 구현에 의존한다.
- Testcontainers 실연동은 대표 round trip으로 제한하고, 대규모 throughput benchmark는 별도 이슈로 분리한다.
- #878에서 누적된 stacked diff가 #879 PR에서도 ecosystem 경계에 포함되도록 #879 scope에 #878의 README, Redis,
  lesson/review 경로를 함께 선언하고 fresh coordinator receipt를 발행했다. exact `--pr-scope` checker는
  `PASS ecosystem-reuse inventory and train contract`를 반환했다.

## 리뷰 결론

2.0.0용 기존 Kafka request-reply 예제를 유지하면서 callback 기반 producer API의 coroutine 소비 경계를 추가한
Type B 변경으로 판단한다. hosted CI와 exact-head 리뷰가 통과하면 병합 가능하다.
