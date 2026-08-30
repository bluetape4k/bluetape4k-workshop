# Issue #863 graph-io checkpoint/resume 소비자 경계

## Context

`bluetape4k-dependencies`를 `2.0.0-SNAPSHOT`으로 올린 뒤
`graph/io-pipeline`은 CSV·Jackson 3 NDJSON·GraphML importer를 사용하지만,
실패한 import를 같은 대상 graph에서 재개하는 예제가 없었다. upstream
`bluetape4k-graph` Issue #537과 merged PR #591에서
`GraphImportCheckpointSession`과 `GraphImportOptions`의 checkpoint 계약을
확인했다.

## Decision or Finding

- `GraphIoPipeline`의 세 import 메서드에 `GraphImportOptions`를 선택 인자로
  노출하고, 기본 호출은 기존 CSV scratch graph import/copy 경로를 유지한다.
- checkpoint를 켠 CSV 경로만 대상 graph에 직접 기록한다. 복원된 external ID
  map이 이미 기록한 backend vertex를 가리켜야 하기 때문이다.
- Jackson 3 NDJSON과 GraphML은 importer가 이미 대상 graph에 직접 기록하므로
  동일한 options를 전달한다.
- source identity와 options fingerprint가 바뀌면
  `GraphImportCheckpointConflictException`으로 재개를 거부한다.
- graph와 checkpoint store가 atomic transaction을 공유하지 않으면 재개는
  at-least-once다. partial target state를 허용하고 안정적인 external ID 또는
  unique constraint를 사용해야 한다.
- `InMemoryGraphImportCheckpointStore` 테스트로 실패 후 재개, 옵션 충돌,
  active claim fencing, close 후 claim release를 고정했다.

## Outcome

기존 사용자는 메서드 인자를 바꾸지 않고 계속 실행할 수 있으며, 필요할 때만
checkpoint store·key·stable source identity를 추가한다. CSV·NDJSON·GraphML 모두
실패한 edge 이후 이미 생성한 vertex를 중복 생성하지 않고 재개한다. 정상 완료 시
checkpoint는 삭제되고, claim을 잃은 시도의 progress write는 차단된다.

## Verification

- `./gradlew :graph-io-pipeline:test --no-daemon --console=plain`: 15개 테스트 통과
- checkpoint 회귀 테스트 4개: CSV·Jackson 3·GraphML resume 및 claim fencing 통과
- `git diff --check`: 통과
- `./gradlew :graph-io-pipeline:detekt`: 이 모듈에는 `detekt` task가 없어 실행 불가함을
  확인했으며, 이를 성공으로 간주하지 않았다.
- `scripts/smoke-validate.sh`의 `all-smoke`, `.github/workflows/Examples.yml`의
  path filter·Gradle test·artifact 경로에는 `graph/io-pipeline`이 이미 등록되어
  별도 변경이 필요하지 않음을 확인했다.

## Future Guidance

checkpoint 예제를 durable store로 확장할 때는 `claim`, `release`, `save`,
`delete` 모두 현재 attempt id를 조건으로 하는 atomic fencing을 유지한다. source
payload를 수정해 재개할 때는 파일 경로·mtime 해시에 의존하지 말고 같은 논리 입력을
나타내는 명시적 `checkpointSourceIdentity`를 전달한다. graph 쓰기와 checkpoint
쓰기를 하나의 transaction으로 묶지 못하면 exactly-once를 문서나 메트릭에서
주장하지 않는다.
