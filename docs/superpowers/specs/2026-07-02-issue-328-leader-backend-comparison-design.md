# Issue #328 - 리더 백엔드 비교 연구실 설계

**날짜**: 2026-07-02
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/328
**마일스톤**: 1.3.1
**상태**: 구현 계획 준비 완료

## 목표

학습자가 선택하는 데 도움이 되는 `leader/backend-comparison-lab` 워크숍 모듈을 추가합니다.
Redis, ZooKeeper 및 Kubernetes 임대 리더 선택 백엔드 사이.

모듈은 기존 백엔드별 예시를 대체해서는 안 됩니다.

- `leader/leader-election`은 Redis 실행 가능한 통합 예시로 남아 있습니다.
- `leader/leader-zookeeper`은 ZooKeeper 실행 가능한 통합 예시로 남아 있습니다.
- `leader/k8s-lease-micrometer`은 Kubernetes 임대 + Micrometer로 유지됩니다.
  실행 가능한 예.

대신 이 실습에서는 소스 기반 비교 레이어를 제공합니다.
리더 보호 예약 작업 모델, 결정적 failover/handoff 시나리오,
백엔드 기능 테이블과 기존 세 가지 기능을 연결하는 README 다이어그램
예.

## 소스 증거

| 소스 | 증거 |
|--------|----------|
| GitHub 발행 #328 | 백엔드 병렬 비교, 공통 보호 작업 추상화, failover/handoff 시나리오, metrics/event 비교, 안정 대 미리 보기 지침, README 로케일 패리티 및 결정론적 기본 테스트가 필요합니다. |
| `leader/leader-election/README.md` | Redis은 `LettuceLeaderElector`, `ListeningLeaderElector`, `LettuceSuspendLeaderElector`, `runIfLeader(lockName)`, TTL형 `waitTime`/`leaseTime`, 이벤트 listener/Flow 관측을 사용한다. |
| `leader/leader-zookeeper/README.md` | ZooKeeper는 큐레이터, 세션 바인딩 임시 znode, 단일 리더 및 그룹 리더 변형을 사용하며 Redis 스타일 TTL이 없습니다. `sessionTimeoutMs` 다음에 장애 조치가 수행됩니다. |
| `leader/k8s-lease-micrometer/README.md` | Kubernetes 임대 경로는 선택 사항입니다. 기본 테스트에서는 `DisabledLeaderCoordinator`을 사용합니다. 모듈은 애플리케이션 수준 미터를 기록하고 업스트림 `leader-micrometer` 미터의 이름을 지정합니다. |
| `docs/lessons/2026-05-23-issue-106-leader-election.md` | 올바른 Redis 패키지 경로는 `io.bluetape4k.leader.lettuce.LettuceLeaderElector`입니다. `runIfLeader`는 건너뛰면 `null`을 반환합니다. Redis 테스트는 백엔드 통합이 필요할 때 `RedisServer.Launcher.redis`를 사용해야 합니다. |
| `docs/lessons/2026-05-25-leader-zookeeper.md` | ZooKeeper에는 TTL이 없습니다. Redis `leaseTime` 가정을 복사하지 마세요. 장애 조치 설명을 위해 세션 손실 의미 체계를 사용합니다. |
| `docs/lessons/2026-06-29-issue-289-k8s-lease-micrometer.md` | 실제 Kubernetes 액세스 선택을 유지하세요. 기본 연기 경로는 결정적으로 유지되어야 합니다. |
| `bluetape4k-leader` 소스 | `LeaderElector.runIfLeader`은 선택되었을 때만 작업을 실행하고 획득하지 못한 경우 `null`를 반환합니다. `LeaderElectionOptions`은 bluetape4k 도우미를 사용하여 유효성을 검사합니다. Kubernetes 옵션은 네임스페이스를 검증하고 지연을 재시도합니다. |
| `bluetape4k-leader` 벤치마크 문서 | 크로스 백엔드 비교는 생산 순위가 아닌 현지 증거입니다. README 권장사항은 비교표와 관련하여 주의해야 합니다. |

## 브레인스토밍 요약

### 접근 방식 A - 문서로만 비교

기존 모듈을 요약하는 README 페이지와 다이어그램만 만듭니다.

**거부됨**: 비교 매트릭스를 만족하지만 제공하지 않습니다.
결정론적 failover/handoff 시나리오 테스트로 인해 학습자는
현지에서 연구실을 운영합니다.

### 접근 방식 B - 전체 실행 가능한 백엔드 매트릭스

하나의 모듈에서 Redis, ZooKeeper 및 Kubernetes 임대 백엔드를 시작하고
모든 실제 선거인을 통해 동일한 작업을 수행합니다.

**거부됨**: 기존 모듈 3개를 복제하고 기본값 CI으로 설정합니다.
컨테이너가 많고 Kubernetes 자격 증명을 기본 경로 위험으로 만듭니다. 이것도
백엔드가 많은 검사에 태그를 지정하거나 범위를 지정해야 하는 이슈 요구 사항을 위반합니다.
기본 테스트 외부.

### 접근 방식 C - 옵트인 백엔드 링크를 사용한 결정론적 비교 랩

다음을 사용하여 작은 Spring Boot 워크샵 모듈을 만듭니다.

- 공통 `LeaderGuardedSchedulerLab` 모델;
- Redis, ZooKeeper 및 Kubernetes 임대용 백엔드 프로필
- 선택됨, 생략됨, 실패함, 만료됨 및 만료됨에 대한 결정론적 시나리오 시뮬레이션
  핸드오프 시도;
- metrics/event 실제 예와 일치하는 카탈로그 행;
- README/README.ko 및 학습자에게 실제 내용을 알려주는 생성된 다이어그램
  실습 통합을 위한 백엔드 모듈.

**선택됨**: 기존 예제를 대체하지 않고  #328을 만족합니다. 그것
실제 교육을 진행하면서 기본 테스트를 로컬 및 결정론적으로 유지합니다.
백엔드 선택 장단점.

## 설계

### 기준 치수

```text
leader/backend-comparison-lab/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/leader/backendcomparison/
    BackendComparisonLabApp.kt
    domain/BackendProfile.kt
    domain/BackendCapability.kt
    domain/LeaderScenario.kt
    domain/LeaderScenarioReport.kt
    service/LeaderBackendCatalog.kt
    service/LeaderFailoverLab.kt
  src/main/resources/application.yml
  src/test/kotlin/io/bluetape4k/workshop/leader/backendcomparison/
    service/LeaderBackendCatalogTest.kt
    service/LeaderFailoverLabTest.kt
  src/test/resources/junit-platform.properties
  src/test/resources/logback-test.xml
```

Gradle 프로젝트는 `includeModules("leader", false, true)`에 의해 자동 등록됩니다.
`:leader-backend-comparison-lab`으로.

### 런타임 모델

`LeaderBackendCatalog`은 변경 불가능한 소스 지원 백엔드 프로필을 제공합니다.

- Redis 양상추: 안정적인 백엔드; TTL/lease-based 회복; 이벤트 리스너 및
  Flow 관찰은 `ListeningLeaderElector`을 통해 가능합니다.
- ZooKeeper 큐레이터: 안정적인 백엔드; 세션 바운드 복구; 단일 리더 및
  그룹 리더 경로; Redis 스타일 TTL이 없습니다.
- Kubernetes 임대: preview/opt-in 워크샵 백엔드; Kubernetes API 객체
  소유권; Micrometer 데코레이터 및 애플리케이션 수준 미터.

`LeaderFailoverLab`은 이러한 프로필에 대해 결정적 시나리오를 실행합니다. 그것
분산 잠금을 구현하지 않습니다. 학습자가 볼 수 있는 계약을 모델링합니다.
어떤 노드가 실행되는지, 어떤 노드가 건너뛰는지, 어떤 상태 변경으로 인해 핸드오프가 발생하는지 등
metric/event 행을 검사해야 합니다.

### 시나리오

| 시나리오 | 목적 | 예상되는 로컬 동작 |
|----------|---------|-------------------------|
| `steady-leader` | 한 인스턴스가 승리하여 보호된 작업을 실행합니다. | 보고서에는 하나의 `executed=true` 이벤트가 있고 팔로어 건너뛰기가 있습니다. |
| `contention-skip` | 팔로어는 동일한 예약된 작업을 실행하지 않습니다. | 보고서는 백엔드별 이유 텍스트가 포함된 팔로어를 건너뛴 것으로 기록합니다. |
| `action-failure-release` | 실패한 보호 작업은 다음 적격 실행을 숨겨서는 안 됩니다. | 기록 실패를 보고하고 recovery/handoff 다음 후보자에게 보고합니다. |
| `backend-loss-handoff` | 백엔드별 장애 조치 트리거는 백엔드마다 다릅니다. | Redis은 임대 만료를 사용하고, ZooKeeper는 세션 손실을 사용하고, Kubernetes는 임대 expiry/resource 업데이트를 사용합니다. |

### 백엔드 매트릭스

README 테이블은 다음을 구별해야 합니다.

- 백엔드 프리미티브;
- 장애 조치 트리거;
- 예상되는 장애 조치 조정 손잡이;
- metrics/events 워크샵 모듈에서 사용 가능;
- 기본 테스트 범위;
- 언제 선택할 것인가?
- 선택하지 않을 때;
- 실제 백엔드 실습을 위한 타겟 모듈입니다.

모듈은 로컬 비교 증거를 학습 지침으로 설명해야 합니다.
생산실적 순위로.

### 다이어그램

`docs/images/readme-diagrams/` 아래에 두 개의 README 다이어그램을 만듭니다.

1. `leader-backend-comparison-lab-readme-architecture-01.svg/png`
   - 정적 소유권 보기: 학습자, 비교 실습, 백엔드 프로필, 기존
     Redis/ZooKeeper/Kubernetes 모듈, metric/event 참고.
   - 커넥터 스타일이 다른 경우 범례를 포함해야 합니다.
2. `leader-backend-comparison-lab-readme-sequence-01.svg/png`
   - 모범 사례 시퀀스 스타일을 확립했습니다.
   - 하나의 예약된 틱, 리더 실행, 팔로어 건너뛰기, 백엔드 손실을 표시합니다.
     핸드오프 및 metric/event 캡처.
   - 투명한 `alt`/`else` 본문, 번호가 매겨진 통화 라벨, 음소거를 사용해야 합니다.
     팔레트와 화살촉이 선 색상과 일치합니다.

다이어그램 작업은 현재 `$bluetape4k-diagram` 체크리스트인 repo-local을 통과해야 합니다.
다이어그램 QA 래퍼, SVG XML 유효성 검사, CairoSVG PNG 렌더링, 전체 크기 PNG
육안 검사, marker/color 감사, 시퀀스 스타일 감사 및 커넥터
해당되는 경우 기하학 감사.

## 논골

- 새로운 실제 리더 선출 백엔드를 구현하지 마세요.
- 기본 테스트에서는 Redis, ZooKeeper, Kubernetes 또는 LocalStack을 시작하지 마세요.
- 기존 리더 모듈을 교체하거나 이름을 바꾸지 마십시오.
- 개별 `bluetape4k-leader` BOM 또는 명시적인 bluetape4k를 추가하지 마세요.
  모듈 버전.
- 프로덕션 벤치마크 순위를 게시하지 마세요.
- `awaitility`을 추가하지 마세요. 결정론적 시나리오에서는 직접 상태를 사용해야 합니다.
  검증문.

## 위험 및 완화

| 위험 | 완화 |
|------|------------|
| 이 연구실은 실제 백엔드 모듈을 가짜로 대체한 것처럼 보입니다. | README 및 클래스 이름은 비교 실습이라고 불러야 하며 실제 백엔드 실습 모듈에 연결되어야 합니다. |
| 시나리오 시뮬레이션은 실제 백엔드 의미론에서 벗어났습니다. | 프로필을 작게 유지하고 소스를 지원하세요. Redis TTL, ZooKeeper 세션, Kubernetes 임대 차이를 README에서 직접 인용하고 테스트합니다. |
| 다이어그램은 확립된 시각적 스타일에 실패합니다. | 현재 모범 사례 참조에서 시작하여 SVG을 PNG로 렌더링하고, 터치된 각 PNG을 시각적으로 검사하고 체크리스트 증거를 기록합니다. |
| CI/smoke 검증에서 새 모듈이 생략되었습니다. | 루트 README 로캘 행, `scripts/smoke-validate.sh all-smoke`, `.github/workflows/Examples.yml` paths/jobs/artifacts를 업데이트하고 `./gradlew projects`을 확인합니다. |
| TDD은 논리가 단순해 보이기 때문에 건너뛰었습니다. | 프로덕션 코드 이전에 카탈로그 및 장애 조치 보고서에 대한 실패한 테스트를 추가합니다. red/green 증거를 기록하세요. |

## 수락 기준

- `leader/backend-comparison-lab`이(가) 존재하며 다음과 같이 나열됩니다.
  `:leader-backend-comparison-lab`.
- 빌드는 버전이 없는 `bluetape4k-dependencies` BOM 루트만 사용합니다.
  카탈로그 별칭.
- 기본 테스트는 결정적이며 실제 백엔드 컨테이너를 시작하지 않습니다.
- 테스트에는 백엔드 프로필 매트릭스, 시나리오 보고서 순서, 건너뛰기 이유,
  작업 실패 복구 및 백엔드 손실 핸드오프 설명.
- `README.md` 및 `README.ko.md`에는 언어 스위치 및 동등한 소스가 포함됩니다.
  백엔드 선택 매트릭스.
- README 다이어그램은 SVG+PNG로 존재하며 다이어그램 체크리스트와 시각적 요소를 통과합니다.
  점검.
- 루트 `README.md` 및 `README.ko.md`은 모듈을 나열합니다.
- CI/example 검증에는 연기 범위의 새로운 결정론적 모듈이 포함되며
  경로 필터.
- 모듈은 학습자를 `leader-election`에 명시적으로 연결합니다.
  실제 백엔드 연습을 위한 `leader-zookeeper` 및 `k8s-lease-micrometer`.

## 확인

- `./gradlew :leader-backend-comparison-lab:test --no-build-cache --rerun-tasks`
- `./gradlew :leader-backend-comparison-lab:compileKotlin :leader-backend-comparison-lab:compileTestKotlin --warning-mode all`
- `./gradlew projects --console=plain`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-readme-language.mjs`
- `./scripts/smoke-validate.sh stale-check`
- `./scripts/smoke-validate.sh diagram-qa`
- `actionlint .github/workflows/Examples.yml .github/workflows/nightly.yml .github/workflows/ci.yml` 워크플로 파일이 변경되는 경우
- `git diff --check`
