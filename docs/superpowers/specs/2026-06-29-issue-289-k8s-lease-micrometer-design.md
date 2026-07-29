# Issue #289 - Kubernetes 임대 Micrometer 워크샵 디자인

**날짜**: 2026-06-29
**문제**: https://github.com/bluetape4k/bluetape4k-workshop/issues/289
**마일스톤**: 1.2.0
**상태**: 구현 준비 완료

## 목표

Kubernetes를 가르치는 `leader/k8s-lease-micrometer` 워크숍 모듈을 추가합니다.
Kubernetes을 요구하지 않고 Micrometer 측정항목을 사용하여 임대 리더 선택
기본 빌드용 클러스터입니다.

학습자는 다음을 이해해야 합니다.

- Spring Boot 4 서비스가 ID, 네임스페이스, 임대 기간, 대기를 매핑하는 방법
  시간, 재시도 지연 및 스케줄러 간격을 `KubernetesLeaseOptions`에 넣습니다.
- 보호된 코루틴 작업이 선택된 인스턴스에서만 실행되는 방식
- Micrometer 미터는 리더십 상태를 설명하고, 하트비트를 갱신하고, 갱신합니다.
  실패, 건너뛴 틱 및 작업 실행
- kind/K3s/manual 실행을 위해 실제 Kubernetes 클라이언트를 선택하는 방법;
- `coordination.k8s.io/v1` 임대 객체에 필요한 RBAC 권한.

## 소스 증거

| 소스 | 증거 |
|--------|----------|
| GitHub 이슈 #289 | Kubernetes 임대 리더십, Micrometer 지표, 결정론적 기본 테스트, 선택 Kubernetes 경계, RBAC 문서 및 README 로케일 패리티가 필요합니다. |
| `leader-k8s` 소스 | `KubernetesLeaseOptions`, `KubernetesLeaseSuspendLeaderElector` 및 `KubernetesClient.suspendRunIfLeader(...)`을 제공합니다. |
| `leader-micrometer` 소스 | `shedlock.leader.acquired`, `shedlock.leader.not_acquired`, `shedlock.leader.duration` 및 `shedlock.leader.active`와 같은 데코레이터 측정항목을 제공합니다. |
| `bluetape4k-leader`의 `examples/k8s-lease` | Fabric8 유형의 `coordination.k8s.io/v1` 임대 API를 사용하고 실제 K3s 테스트에 옵트인으로 태그를 지정합니다. |
| `bluetape4k-leader`의 `examples/prometheus-dashboard` | 명시적인 Micrometer 사전 등록 및 Prometheus 액추에이터 노출을 보여줍니다. |
| 워크샵 레포 규칙 | 새 모듈에는 README/README.ko 패리티, 생성된 PNG/SVG 다이어그램, 검증 매트릭스 업데이트 및 일반 CI에 추가 시 연기 방지 테스트가 필요합니다. |

## 논골

- 종류, K3, minikube 또는 라이브 Kubernetes API를 요구하지 마세요.
  `:leader-k8s-lease-micrometer:test`.
- Redis 또는 ZooKeeper 리더 예제를 바꾸지 마십시오.
- 개인 `bluetape4k-leader` BOM을 소개하거나 노골적으로 언급하지 마세요.
  bluetape4k 모듈 버전.
- 워크샵에서 사용자 정의 Kubernetes 임대 알고리즘을 구현하지 마십시오. 그만큼
  옵트인 실제 경로는 `bluetape4k-leader-k8s`에 위임됩니다.
- 포드 보안 정책 등 프로덕션 강화를 약속하지 말고 재시도하세요.
  예산, 다중 클러스터 장애 조치 또는 승인 제어 정책.

## 설계

### 기준 치수

```
leader/k8s-lease-micrometer/
  README.md
  README.ko.md
  build.gradle.kts
  src/main/kotlin/io/bluetape4k/workshop/leader/k8slease/
    K8sLeaseMicrometerApp.kt
    config/K8sLeaseMicrometerConfig.kt
    config/K8sLeaseMicrometerProperties.kt
    leader/LeaderCoordinator.kt
    job/K8sLeaseGuardedTask.kt
    metrics/K8sLeaseMetrics.kt
  src/main/resources/application.yml
  src/test/kotlin/io/bluetape4k/workshop/leader/k8slease/
    config/K8sLeaseMicrometerPropertiesTest.kt
    job/K8sLeaseGuardedTaskTest.kt
    metrics/K8sLeaseMetricsTest.kt
```

Gradle 프로젝트는 `includeModules("leader", false, true)`에 의해 자동 등록됩니다.
`:leader-k8s-lease-micrometer`으로.

### 런타임 모델

`LeaderCoordinator`은 작은 애플리케이션 경계입니다.

- `DisabledLeaderCoordinator`은 기본값이며 항상 건너뜁니다. 그것은
  Spring 컨텍스트와 모든 테스트는 Kubernetes 자격 증명 없이 실행됩니다.
- `KubernetesLeaderCoordinator`은(는) 다음 경우에만 생성됩니다.
  `workshop.leader.k8s.enabled=true`. Fabric8 클라이언트를 소유하고
  `KubernetesLeaseSuspendLeaderElector`.

예약된 서비스 호출:

1. 가드 시도를 기록합니다.
2. `runIfLeader(lockName)`에 전화하세요;
3. 선출되면 리더십을 활성으로 표시하고 결정론적 코루틴 작업을 실행하며
   기록 작업 duration/execution 개수;
4. 건너뛰면 건너뛴 카운터가 증가합니다.
5. 선택된 블록이 완료된 후 항상 JVM-로컬 활성 게이지를 재설정하십시오.

### 측정항목

작업장 미터:

| 미터 | 유형 | 태그 | 의미 |
|-------|------|------|---------|
| `workshop.k8s.lease.leader.active` | 게이지 | `lock.name`, `namespace` | JVM-로컬 활성 리더 작업 상태. |
| `workshop.k8s.lease.guard.attempts` | 카운터 | `lock.name`, `namespace` | 예정된 가드 시도. |
| `workshop.k8s.lease.guard.skipped` | 카운터 | `lock.name`, `namespace`, `reason` | 작업을 실행하지 않은 시도입니다. |
| `workshop.k8s.lease.renew.attempts` | 카운터 | `lock.name`, `namespace` | 선출된 작업이 활성 상태인 동안 내보내는 데모 하트비트입니다. |
| `workshop.k8s.lease.renew.failures` | 카운터 | `lock.name`, `namespace`, `reason` | 작업 경계에 의해 나타나는 데모 하트비트 오류입니다. |
| `workshop.k8s.lease.task.executions` | 카운터 | `lock.name`, `namespace`, `result` | 보호된 작업 결과. |
| `workshop.k8s.lease.task.duration` | 타이머 | `lock.name`, `namespace` | 보호된 작업 기간. |

README는 또한 업스트림 `leader-micrometer` 데코레이터 미터의 이름을 지정합니다.
학습자는 애플리케이션 수준의 워크숍 미터와 도서관 수준의 미터를 구별할 수 있습니다.
수단.

### Kubernetes 경계

실제 Kubernetes 경로는 선택 사항입니다.

```yaml
workshop:
  leader:
    k8s:
      enabled: true
      namespace: workshop
      identity: orders-api-1
      lease-name: orders-nightly-export
```

Lease 리소스에 필요한 RBAC 동사:

- `get`
- `list`
- `watch`
- `create`
- `update`
- `patch`
- `delete`

기본 테스트는 해당 권한을 사용하지 않으며 클러스터를 건드리지 않습니다.

## 확인

- `./gradlew :leader-k8s-lease-micrometer:test`
- `./gradlew :leader-k8s-lease-micrometer:compileTestKotlin --warning-mode all`
- `./gradlew projects`
- `node scripts/validate-readme-parity.mjs`
- `node scripts/validate-readme-language.mjs`
- `git diff --check`
