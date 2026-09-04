# Issue #874 SQS Observation 재사용 경계 검토

## 검토 범위

- 기존 `aws/sqs-sns-coroutines` listener의 `ObservationRegistry`와 trace context
- `bluetape4k-dependencies:2.0.0` BOM 아래 SQS observation alias 사용
- manual acknowledgement, heartbeat, cancellation, local queue fixture 계약

## 결정

예제는 opt-in observation listener에서 receive/process/acknowledgement 단계를
각각 기록하고, parent observation context를 handler까지 전달한다. 정상 처리는
acknowledgement 후 종료하고 cancellation은 visibility 변경 없이 취소 상태로
남긴다. 운영 exporter와 실제 AWS queue 권한·DLQ 정책은 범위 밖으로 남긴다.

## 검증 증거

- module targeted/full tests와 root build/Detekt 통과
- README parity/language, stale-check, actionlint, `git diff --check` 통과
- PR #925가 `develop`을 대상으로 하며 이 scope의 expected head ref를 사용
