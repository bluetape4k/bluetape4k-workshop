# #888 native graph algorithm 실행 관찰 설계

## 목적

`graph/abuser-detection`이 `bluetape4k-graph 2.0.0`의 algorithm provider
선택·관찰 계약을 소비해 PageRank 점수와 실제 실행 경로를 함께 반환하도록 확장한다.
기존 `rankSuspiciousUsers(limit)`의 결과와 정렬 계약은 유지하며, 실행 관찰이 필요한
호출자만 새 API를 사용한다.

## 근거와 현재 upstream 계약

- Workshop Issue #888과 `bluetape4k-graph` Issue #478, merged PR #524를 기준으로
  한다.
- `GraphAlgorithmProviderSelector`는 `AUTO`, `JVM_ONLY`, `NATIVE_ONLY` 정책을
  `GraphAlgorithmExecution`으로 변환한다. `JVM_FALLBACK`은 반드시
  `fallbackReason`을 가지며 `NATIVE`는 fallback reason을 가질 수 없다.
- 현재 2.0.0의 Neo4j와 Memgraph 구현은
  `GraphAlgorithmExecutionObservable.lastAlgorithmExecution`으로 PageRank의 JVM
  fallback을 노출한다. TinkerGraph는 같은 observable을 구현하지 않지만 PageRank를
  JVM에서 실행한다.
- 현재 기본 backend에는 GDS/MAGE native 실행 구현이 없다. 따라서 descriptor만
  만들어 native 실행처럼 보고하지 않으며 `NATIVE_ONLY`는 PageRank 실행 전에
  `GraphAlgorithmProviderUnavailableException`으로 실패한다.

## API 결정

- `SuspiciousUserRanking`을 추가해 기존 `List<SuspiciousUserScore>`와 호출별
  `AbuserAlgorithmExecution`을 한 결과로 묶는다. 실행 모델의 `providerId`는
  `[a-z0-9][a-z0-9._-]{0,63}`만 허용해 응답·로그·메트릭 경계를 bounded하게
  유지한다.
- blocking 서비스에
  `rankSuspiciousUsersWithExecution(limit, policy)`를 추가한다.
  - 서비스가 호출마다 `GraphAlgorithmProviderSelector`로 실행 결정을 먼저 만든다.
    현재 workshop에는 native executor가 없으므로 provider 목록은 비어 있다.
  - `AUTO`와 `JVM_ONLY`는 PageRank를 정확히 한 번 실행하고 점수 정렬을 기존
    메서드와 동일하게 유지한다. `JVM_ONLY`의 reason은 `JVM_ONLY_POLICY`, `AUTO`의
    reason은 `NO_PROVIDER`다.
  - `NATIVE_ONLY`는 현재 구성된 native provider가 없으므로 selector에서 먼저
    실패하고 PageRank를 호출하지 않는다.
- 두 서비스는 생성자에서 선택적으로 받은 `GraphAlgorithmExecutionObserver`에 성공한
  실행 결정을 동기적으로 한 번 전달한다. observer의 `Exception`은 PageRank 성공을
  실패로 바꾸지 않도록 경고만 남기며, coroutine cancellation은 observer 호출 전에
  그대로 전파한다.
- backend의 `GraphAlgorithmExecutionObservable.lastAlgorithmExecution`은 공유 mutable
  상태라 호출 correlation이 없다. 서비스 결과와 callback의 근거로 사용하지 않는다.
  이 경계 덕분에 동시 호출도 다른 호출의 메타데이터를 읽지 않는다.
- coroutine 서비스에는 동일 인자를 받는 cold
  `Flow<SuspiciousUserRanking>`이 아니라 단일 suspend 결과를 반환하는
  `suspend fun rankSuspiciousUsersWithExecution(...)`을 추가한다. 실행 메타데이터는
  전체 PageRank 호출 단위이므로 각 점수마다 중복하지 않는다.
- 기존 `rankSuspiciousUsers(limit)`은 새 API에 의존하지 않고 현재 반환 타입과 cold
  Flow 계약을 그대로 유지한다.

## 거부한 대안

1. `SuspiciousUserScore`마다 provider 정보를 복제하면 실행 단위 메타데이터가 행마다
   반복되고 기존 DTO 호환성을 깨므로 사용하지 않는다.
2. 생성자 callback만 추가하면 호출자가 점수와 callback 순서를 직접 결합해야 하고
   동시 호출에서 결과 귀속이 불명확하므로 사용하지 않는다.
3. descriptor-only provider를 주입해 native 경로를 흉내 내면 selector capability와
   실제 backend 실행을 혼동하므로 사용하지 않는다.

## 오류와 정보 노출 경계

- 공개 결과에는 enum 기반 `algorithm`, `path`, `fallbackReason`과 검증된
  `providerId`만 포함한다. backend exception, native payload, query, credential은
  포함하지 않는다. raw upstream execution 객체도 공개 결과에 저장하지 않는다.
- observer callback의 일반 `Exception`은 native 실패로 해석하지 않고 raw Throwable
  없이 안정적인 경고만 남긴다. `CancellationException`은 그대로 전파하며 backend의
  마지막 실행 상태는 읽지 않는다.
- suspend 호출이 PageRank collection 완료 뒤 callback 직전까지 취소되면 성공
  execution을 보고하지 않는다. callback이 시작된 뒤 들어온 취소와 callback 완료의
  순서는 경합할 수 있지만 callback은 최대 한 번만 호출되고 취소된 호출은 결과를
  반환하지 않는다.
- `NATIVE_ONLY` 실패는 조용한 fallback으로 바꾸지 않는다.
- TinkerGraph의 malformed 문자열 ID는 2.0.0에서 `GraphQueryException`인 backend
  입력 계약이다. 기존 workshop의 “없는 ID” 테스트는 numeric missing ID를 사용해
  누락과 malformed 입력을 구분한다.

## 테스트 전략

1. 기존 fixture에서 새 blocking/suspend API의 점수·정렬·limit가 기존 API와
   일치하는지 검증하고 `limit`이 `PageRankOptions.topK`로 전달되는지 확인한다.
2. TinkerGraph `AUTO`가 `jvm-fallback`, `JVM_FALLBACK`, `NO_PROVIDER`를 반환하는지
   검증한다.
3. `JVM_ONLY`가 `JVM_ONLY_POLICY`를 반환하는지 검증한다.
4. `NATIVE_ONLY`가 PageRank 실행 전에 실패하고 observer도 호출하지 않는지 recording
   fake로 검증한다.
5. blocking/suspend recording fake로 PageRank가 한 번만 실행되고 observer가 같은
   호출별 execution을 한 번 받는지 검증한다. 동시 호출에서도 공유된 마지막 실행
   상태를 읽지 않는 구조를 고정한다.
6. suspend collection과 callback 직전 경계를 취소하면 `CancellationException`이
   전파되고 observer가 성공 실행을 보고하지 않는지 검증한다. callback 시작 뒤
   취소 경합에서는 at-most-once와 결과 미반환을 검증한다.
7. CR/LF, NUL, tab, 기타 제어문자, 64자를 넘는 provider ID를 거부하고 정확히 64자
   safe ID는 허용하는지 검증한다.
8. numeric missing ID로 기존 endpoint/cluster 계약을 재검증하고 malformed ID의
   `GraphQueryException`은 upstream 계약으로 별도 고정한다.

## 문서와 운영 표면

- module/root 양 언어 README, coverage matrix, Examples workflow의 graph smoke 설명,
  stale-check, ecosystem reuse manifest와 lesson을 같은 branch에서 갱신한다.
- consumer 의존성은 root `platform(libs.bluetape4k.dependencies)`만 사용하며 graph
  alias에 버전을 추가하거나 native SDK를 도입하지 않는다.
- 기본 smoke는 TinkerGraph만 사용한다. Neo4j/Memgraph container 검증은 기존 full
  integration 경계를 유지한다.

## 범위 밖

GDS/MAGE provider 구현, native SDK·container 추가, PageRank 품질 재튜닝, HTTP/CLI
adapter 추가, upstream TinkerGraph observable 변경은 다루지 않는다.

## 롤백

새 결과 모델과 두 서비스의 opt-in API, 관련 테스트·문서를 되돌리면 된다. 기존
PageRank API와 저장 데이터에는 변경이 없어 migration은 필요하지 않다.
