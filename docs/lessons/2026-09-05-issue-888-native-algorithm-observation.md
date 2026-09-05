# Issue #888 native graph algorithm 실행 관찰

## Context

`bluetape4k-graph` 2.0.0은 `GraphAlgorithmProviderSelector`, provider policy,
실행 경로와 fallback reason을 제공한다. 기존 `graph/abuser-detection` 예제는 PageRank
점수만 반환해 운영자가 특정 호출이 native provider인지 JVM fallback인지 구분할 수
없었다. 또한 TinkerGraph 2.0.0은 숫자로 해석할 수 없는 정점 ID를 누락 정점이 아니라
`GraphQueryException`인 malformed 입력으로 처리한다.

## Decision or Finding

- Missing vertex fixture에는 backend가 해석할 수 있지만 존재하지 않는 숫자 ID를 쓴다.
  Malformed 문자열 ID는 TinkerGraph 전용 계약 테스트로 분리한다.
- `rankSuspiciousUsersWithExecution`은 selector 결과와 점수를
  `SuspiciousUserRanking` 한 객체에 묶는다. Backend의 공유
  `lastAlgorithmExecution`은 동시 호출의 attribution을 보장하지 않으므로 사용하지 않는다.
- 현재 consumer 예제에는 GDS/MAGE SDK나 native executor가 없다. 따라서 `AUTO`는
  `NO_PROVIDER`, `JVM_ONLY`는 `JVM_ONLY_POLICY`로 JVM fallback하며 `NATIVE_ONLY`는
  PageRank 실행 전에 실패한다.
- Provider ID는 `[a-z0-9][a-z0-9._-]{0,63}`로 제한한다. Observer 일반 예외에는 원문
  메시지나 provider ID를 기록하지 않고 안정적인 경고만 남긴다.
- Suspend 경로는 Flow 수집 뒤 observer 호출 직전과 직후에 취소 상태를 확인한다. 수집 중
  취소된 호출은 결과나 observer event를 남기지 않는다. Callback 시작과 취소가 경합하면
  event는 최대 한 번 발생할 수 있지만 취소된 호출은 결과를 반환하지 않는다.

## Outcome

기존 `rankSuspiciousUsers`의 점수·정렬·순위 계약은 그대로 유지하면서 blocking/suspend
호출이 자신의 실행 경로를 직접 반환한다. 20개 동시 호출 테스트에서 `AUTO`와
`JVM_ONLY` 결과가 각 요청 policy에 정확히 귀속되고 observer event 수가 성공 호출 수와
같음을 검증했다.

## Verification

- `./gradlew :graph-abuser-detection:test --no-build-cache --rerun-tasks --max-workers=1 --no-daemon`
  - 66 tests passed
- `./gradlew :graph-abuser-detection:integrationTest --no-build-cache --rerun-tasks --max-workers=1 --no-daemon`
  - Neo4j·Memgraph 94 tests passed
- `./gradlew detekt --no-build-cache --rerun-tasks --max-workers=1 --no-daemon`
  - 108 tasks executed, build successful
- `NATIVE_ONLY`에서 PageRank 호출 0회
- 수집 중 취소에서 observer 호출 0회, callback 시작 경합에서 event 최대 1회·결과 반환 0회
- TinkerGraph numeric missing과 malformed ID 계약 분리

## Future Guidance

Native provider를 실제로 추가할 때는 selector와 executor를 같은 호출 scope에 두고,
실행 성공 뒤 반환하는 결과와 observer event에 동일한 execution을 사용한다. 공유 mutable
실행 상태를 나중에 조회해 응답에 결합하지 않는다. 외부 SDK를 추가하려면 workshop
consumer의 BOM-only 정책과 별도의 Type A 의존성 검토를 먼저 통과해야 한다.
