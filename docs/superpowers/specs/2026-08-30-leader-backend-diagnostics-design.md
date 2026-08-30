# Leader backend diagnostics observability 설계

## 문서 상태

- 대상 이슈: [#866](https://github.com/bluetape4k/bluetape4k-workshop/issues/866)
- 대상 모듈: `leader/backend-comparison-lab`
- 기준 브랜치: `develop`
- 기준 의존성: `bluetape4k-dependencies:2.0.0-SNAPSHOT`
- 언어 범위: 소스 KDoc과 README 두 locale, 설계 문서는 한국어

## 문제와 근거

현재 lab은 Redis, ZooKeeper, Kubernetes Lease의 정적 비교표와 결정론적인
failover report만 제공합니다. 운영 경계에 backend health check가 필요하다고
README에 적혀 있지만, 선택한 profile을 Spring 운영 표면에 연결하거나 probe
결과를 관찰할 수 없습니다.

upstream `bluetape4k-leader`의 현재 `develop`에는 다음 계약이 있습니다.

- `LeaderBackendDiagnosticsProvider`가 외부 I/O 없는 descriptor와 opt-in
  `diagnostics(probe = true, timeout)`를 제공합니다.
- `LeaderBackendDiagnosticsProbe`가 `UP`, `DOWN`, `UNKNOWN`을 bounded reason과
  함께 만들고 일반 예외를 `PROVIDER_EXCEPTION`으로 정규화합니다. 취소와
  `InterruptedException`은 다시 전달합니다.
- `leader-spring-boot`의 `LeaderBackendDiagnosticsEndpoint`는 정적 호출에서
  `NOT_CHECKED`를 반환하고, `LeaderBackendHealthIndicator`는 active probe를
  Spring `UP/DOWN/UNKNOWN`으로 매핑합니다.
- `leader-micrometer`의 decorator는 active probe마다
  `leader.backend.connectivity` counter를 `backend`, `status`, `reason`이라는
  low-cardinality tag로 기록합니다.

이 설계는 위 API를 workshop 예제에 연결하되 Redis, ZooKeeper, Kubernetes
자격 증명이나 네트워크가 기본 테스트에 들어오지 않도록 한다.

## 목표와 제외 범위

### 목표

1. root `bluetape4k-dependencies` BOM에서 `leader-spring-boot`와
   `leader-micrometer`를 versionless alias로 소비한다.
2. 기존 catalog의 선택 profile을 diagnostics descriptor로 변환하고,
   Spring Boot Actuator 정적 endpoint에 연결한다.
3. 기본 경로는 passive diagnostics(`NOT_CHECKED`)로 유지한다. health 호출로
   활성화되는 bounded probe는 명시적인 `workshop.leader.probe-outcome` 설정으로
   재현한다.
4. health 상태와 Micrometer counter가 동일한 backend id, 상태, reason을
   사용하도록 한다.
5. fake provider 단위 테스트로 정상, 다운, 미확정, 미지원, 일반 예외, 취소와
   timeout 전달을 검증한다.
6. README 두 locale, validation matrix, smoke/full workflow, stale-check,
   lesson을 실제 실행 방법과 함께 갱신한다.

### 제외 범위

- 실제 Redis, ZooKeeper, Kubernetes Lease client 연결과 장애 주입
- lock 획득, lease 갱신, write/action endpoint
- endpoint, token, credential, raw exception message 노출
- 개별 Bluetape 모듈의 수동 버전 고정 또는 release 작업

## 선택한 구조

### 구성 요소

`BackendComparisonLabApp`가 `LeaderBackendDiagnosticsConfiguration`을 import한다.
구성 클래스는 다음 빈을 만든다.

1. `LeaderBackendDiagnosticsProperties`
   - prefix: `workshop.leader`
   - `backend-id`: 기존 `LeaderBackendCatalog`의 안정적인 profile id
   - `probe-outcome`: `UP`, `DOWN`, `UNKNOWN`, `UNSUPPORTED`, `EXCEPTION`,
     `CANCELLED`
2. `ProfiledLeaderElector`
   - 내부 실행은 `LocalLeaderElector`에 위임해 deterministic lab 계약을
     보존한다.
   - `LeaderBackendDiagnosticsProvider`를 직접 구현해 선택 profile의
     descriptor를 반환한다.
   - `LeaderBackendDiagnosticsProbe`로 설정된 outcome을 bounded connectivity
     결과로 변환한다.
3. `InstrumentedLeaderElector`
   - `ProfiledLeaderElector`를 감싸 active probe의
     `leader.backend.connectivity` counter를 등록한다.
   - 기존 `LeaderBackendDiagnosticsAware` 경계를 통해 Spring selector가
     동일 provider를 찾게 한다.

`leader-spring-boot` auto-configuration은 위 elector를 non-local
`LeaderElectionState` 후보로 선택한다. 따라서 local suspend fallback이 함께
생겨도 diagnostics endpoint와 health indicator는 명시적으로 만든 profile
provider를 사용한다.

### Descriptor 매핑

실제 client를 생성하지 않으므로 upstream backend client의 descriptor를
재사용하지 않고 catalog profile id를 backend id로 사용한다. capability 값은
현재 upstream provider의 정적 계약을 반영한다.

| workshop profile | 실행 모델 | lease extension | audit | clock | TTL/session |
|---|---|---|---|---|---|
| `redis-lettuce` | BLOCKING/ASYNC/SUSPEND | single/group 지원 | 미지원 | BACKEND | SERVER_TTL |
| `zookeeper-curator` | BLOCKING/ASYNC/SUSPEND | single/group 지원 | 미지원 | NOT_APPLICABLE | SESSION |
| `kubernetes-lease` | BLOCKING/ASYNC/SUSPEND | single/group 지원 | single 지원/group 미지원 | PROCESS | CLIENT_LEASE |

catalog의 기존 display name, capability 설명, practice module 링크는 그대로
유지하고 diagnostics 응답의 descriptor만 이 표로 보강한다.

### 운영 표면과 보안 경계

- `management.endpoint.leaderBackendDiagnostics.enabled=true`일 때만 정적
  endpoint를 노출한다.
- `bluetape4k.leader.observability.backend-health.enabled=true`일 때 health
  indicator가 bounded probe를 실행한다. timeout은
  `bluetape4k.leader.observability.backend-health.timeout`으로 설정한다.
- web exposure에는 `health,info,leaderBackendDiagnostics`를 명시한다.
- 기본 endpoint 호출은 probe를 실행하지 않으므로 network I/O가 없다.
- response와 health detail에는 backend id, status, bounded reason, checkedAt,
  latency만 남긴다. exception message, endpoint, token, credential은 저장하거나
  반환하지 않는다.
- `CANCELLED`는 provider 내부 계약을 검증하기 위한 테스트 outcome이다.
  `LeaderBackendHealthIndicator`는 이를 `UNKNOWN`으로 처리하고
  cancellation을 삼키지 않는다.

## 실패 모드와 복구

| 상황 | provider 결과 | health 결과 | metric |
|---|---|---|---|
| passive endpoint | `NOT_CHECKED/NOT_CHECKED` | probe 미실행 | counter 미증가 |
| callback `UP` | `UP/CONNECTED` | `UP` | `UP,CONNECTED` |
| callback `DOWN` | `DOWN/DISCONNECTED` | `DOWN` | `DOWN,DISCONNECTED` |
| callback `UNKNOWN` | `UNKNOWN/CLIENT_STATE_UNCONFIRMED` | `UNKNOWN` | 해당 상태/reason |
| unsupported provider | `UNKNOWN/PROVIDER_UNSUPPORTED` | `UNKNOWN` | 해당 상태/reason |
| 일반 예외 | `UNKNOWN/PROVIDER_EXCEPTION` | `UNKNOWN` | 해당 상태/reason |
| cancellation | `CancellationException` 재전달 | `UNKNOWN` | decorator가 cancellation을 기록하지 않고 재전달 |
| 양수 유한 timeout 아님 | `IllegalArgumentException` | indicator 경계에서 `UNKNOWN` | provider exception 경계와 혼동하지 않음 |

timeout은 wall-clock 강제 장치가 아니라 provider-native bounded budget으로
전달한다. 테스트는 callback이 받은 정확한 timeout과 양수·유한 검증을
확인한다.

## 테스트 계약

테스트는 `ApplicationContextRunner`로 Spring auto-configuration 경계를
검증하고, provider 자체는 별도 단위 테스트로 빠르게 검증한다.

- descriptor와 profile 선택: 세 profile id가 응답에 나타나며 알 수 없는 id는
  context startup을 실패시킨다.
- passive endpoint: `LeaderBackendDiagnosticsEndpoint`가
  `NOT_CHECKED`를 반환하고 provider callback은 호출되지 않는다.
- active health: `UP`, `DOWN`, `UNKNOWN`, unsupported, exception 매핑과
  `backend`, `connectivity`, `reason` detail을 확인한다.
- metrics: active probe 후 `leader.backend.connectivity`가 backend/status/reason
  세 tag만으로 한 번 증가하고 raw exception/credential이 tag나 detail에
  포함되지 않는다.
- timeout: callback에 지정한 250ms budget이 전달되고 0 또는 무한 값은
  거부된다.
- cancellation: fake provider의 `CancellationException`이 원본 인스턴스로
  재전달되고 health indicator는 `UNKNOWN`으로 닫힌다.
- 기본 smoke: Spring context와 모듈 test가 Docker, kubeconfig, credential,
  네트워크 없이 통과한다.

## 호환성, rollback, 재실행

- 모든 Bluetape 의존성은 root `platform(libs.bluetape4k.dependencies)`에서
  해결한다. 새 alias에는 version을 적지 않는다.
- 기존 `LeaderBackendCatalog`, `LeaderFailoverLab`의 public 동작과 profile id는
  유지한다. 변경은 diagnostics adapter, 설정, 문서, 테스트에 한정한다.
- upstream diagnostics API가 아직 artifact에 없으면 implementation을 시작하지
  않고 해당 artifact 배포를 기다린다. 현재 Maven metadata에서
  `bluetape4k-dependencies:2.0.0-SNAPSHOT`와 `leader-spring-boot:1.0.0-SNAPSHOT`
  를 확인했다.
- rollback은 새 alias, configuration class, properties, tests, 문서 변경을
  같은 commit 단위로 되돌리는 것으로 충분하다. 기존 deterministic scenario
  코드는 독립적으로 남는다.
- 실패한 test는 해당 module의 `test`부터 재실행하고, context lifecycle 또는
  metric 중복이 의심되면 `--no-build-cache --rerun-tasks --no-parallel
  --max-workers=1`로 fresh proof를 얻는다.

## 수용 기준과 DoD 매핑

| 이슈 기준 | 설계/검증 산출물 |
|---|---|
| static/active 상태 매핑 | provider + context-runner tests + README 실행표 |
| 민감정보 제거 | descriptor/health/metric assertion과 response field review |
| timeout/cancellation | provider unit tests와 health boundary test |
| credential/network 없는 smoke | ApplicationContextRunner, module test, workflow smoke command |
| BOM only | `libs.versions.toml`, module build script, dependency report |
| README/행렬/workflow/stale-check/lesson | exact-file plan task와 final diff review |

이 문서의 승인 후 구현 계획을 작성하고, 계획 리뷰에서 P0/P1을 0으로 확인한
뒤 TDD 순서로 작업한다.

## 설계 대안과 기각 사유

### A. 실제 Redis/ZooKeeper/Kubernetes client를 profile마다 생성

기각한다. 선택 profile을 실제 client와 매핑하는 장점은 있지만 기본 smoke가
자격 증명·네트워크·컨테이너에 의존하고, 이 이슈가 요구한 deterministic lab의
경계를 깨뜨린다.

### B. workshop 전용 controller/health/metric을 새로 구현

기각한다. 상태 매핑과 metric tag 계약을 중복 구현하면 upstream
`leader-spring-boot`와 drift할 위험이 있다. 현재 제공된 Actuator endpoint,
health indicator, Micrometer decorator를 직접 연결하는 편이 새 기능 적용
예제로 더 정확하다.

### C. 선택안: local delegate + profile diagnostics provider + upstream adapters

선택한다. 실행 semantics는 기존 local deterministic delegate로 고정하고,
새로운 diagnostics observability 표면은 2.0.0 API가 소유한다. 따라서 실제
backend 없이도 선택 profile, 상태 매핑, bounded reason, low-cardinality
metric을 한 경로에서 설명하고 검증할 수 있다.
