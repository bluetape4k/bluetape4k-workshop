# Issue #743 Kinesis 코루틴 워크숍 경계

## Context

AWS SDK v2 Kinesis producer/consumer 예제를 Spring Boot와 코루틴으로 제공하면서도 기본 학습 경로가
자격 증명이나 외부 AWS endpoint를 요구하지 않아야 했다.

## Decision or Finding

- `local` profile은 deterministic in-memory `KinesisOperations` fake를 사용한다.
- `real-aws` profile에서만 upstream `KinesisCoroutinesTemplate`와 `KinesisAsyncClient`를 활성화한다.
- partition key와 shard/sequence는 관측용 report로만 노출하고 exactly-once나 global ordering을 주장하지 않는다.
- collector는 caller-owned cold `Flow`로 두고, demo runner job만 app-owned `SupervisorJob` registry에서 관리한다.
- iterator expiry와 throttle retry를 분리하고, 성공적인 `getRecords` episode에서 retry counter를 초기화한다.
- credential, endpoint, payload, partition key는 log/report/health/metric에 기록하지 않는다.

## Outcome

학습자는 기본 `bootRun`으로 세 record의 publish/consume, cancellation, retry/backoff 경계를 로컬에서 재현할 수 있다.
실제 AWS는 고유 stream과 최소 IAM action을 준비한 뒤 `real-aws` profile을 명시적으로 선택해야 한다.

## Verification

- `./gradlew :aws-kinesis-coroutines:test`
- `./scripts/smoke-validate.sh aws`
- `./scripts/smoke-validate.sh all-smoke`
- local `bootRun`의 exit code 0, 세 sequence 출력, 잔류 process 없음
- 한·영 root README와 module README의 profile/명령/안전 경계 parity

## Future Guidance

Kinesis 학습 예제를 확장할 때도 기본 profile의 credential-free 계약, partition 단위 ordering 설명,
caller-owned cancellation, 명시적 real AWS opt-in을 유지한다. Floci 또는 live AWS 통합을 추가할 경우
비용·정리 절차와 smoke/full workflow 분리를 먼저 갱신한다.
