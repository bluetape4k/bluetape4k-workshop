# Kubernetes Lease Micrometer Workshop

[English](README.md) | 한국어

이 모듈은 Kubernetes `coordination.k8s.io/v1` Lease로 리더를 선출하고
Micrometer로 관측하는 Spring Boot 4 스케줄 작업 예제입니다.

기본 경로는 smoke-safe입니다. `workshop.leader.k8s.enabled=false`이면
disabled coordinator를 사용하므로 테스트와 로컬 컨텍스트 시작에 kind, K3s,
minikube, kubeconfig, service account 자격 증명이 필요하지 않습니다. 실제
Kubernetes Lease 경로는 명시적으로 opt-in해야 합니다.

## Architecture

![Kubernetes Lease Micrometer architecture](../../docs/images/readme-diagrams/leader-k8s-lease-micrometer-readme-architecture-01.png)

`K8sLeaseMicrometerConfig`는 기본으로 `DisabledLeaderCoordinator`를 만듭니다.
`workshop.leader.k8s.enabled=true`이면 Fabric8 `KubernetesClient`,
`KubernetesLeaseSuspendLeaderElector`, upstream `InstrumentedSuspendLeaderElector`를
생성합니다. `K8sLeaseGuardedTask`는 각 리더 guard tick 주변에서 애플리케이션
수준 meter를 기록하는 스케줄 workload입니다.

## Execution Flow

![Kubernetes Lease execution flow](../../docs/images/readme-diagrams/leader-k8s-lease-micrometer-readme-flow-01.png)

1. 모든 인스턴스에서 Spring Scheduling tick이 발생합니다.
2. 작업은 `workshop.k8s.lease.guard.attempts`를 기록합니다.
3. `LeaderCoordinator.runIfLeader(leaseName) { ... }`가 leader block 진입을 시도합니다.
4. follower는 `null`을 받고 `workshop.k8s.lease.guard.skipped`를 증가시킵니다.
5. 선출된 인스턴스는 `workshop.k8s.lease.leader.active=1`로 표시하고,
   workshop heartbeat meter를 기록하고, simulated task를 실행한 뒤 duration을
   기록하고 active gauge를 `0`으로 되돌립니다.
6. 나중에 다른 pod가 같은 Lease 이름을 획득하면 handoff를 관찰할 수 있습니다.

## Configuration

```yaml
workshop:
  leader:
    k8s:
      enabled: false
      namespace: workshop
      identity: ${HOSTNAME:local-workshop}
      lease-name: workshop-nightly-export
      wait-time: 2s
      lease-time: 30s
      retry-delay: 50ms
      job-fixed-delay: 10s
      simulated-work-time: 100ms
      auto-extend: true
```

| Property | 의미 |
|----------|------|
| `enabled` | `true`일 때만 실제 Fabric8 client와 Kubernetes Lease elector를 만듭니다. |
| `namespace` | Lease 객체가 저장될 Kubernetes namespace입니다. |
| `identity` | `LeaderElectionOptions.nodeId`로 전달되는 노드 식별자입니다. |
| `lease-name` | Kubernetes Lease 이름이자 Micrometer `lock.name` tag입니다. |
| `wait-time` | 후보가 이번 tick을 포기하기 전까지 기다리는 시간입니다. |
| `lease-time` | Kubernetes backend가 사용하는 lease duration입니다. `wait-time` 이상이어야 합니다. |
| `retry-delay` | Kubernetes resource-version 경합 시 재시도 지연입니다. |
| `job-fixed-delay` | Spring scheduler fixed delay입니다. |
| `simulated-work-time` | 데모 작업 지연 시간입니다. smoke 테스트에서는 짧게 유지합니다. |
| `auto-extend` | action 실행 중 `bluetape4k-leader` auto-extension 경로를 활성화합니다. |

## Metrics

애플리케이션 수준 workshop meter:

| Meter | Type | Tags |
|-------|------|------|
| `workshop.k8s.lease.leader.active` | gauge | `lock.name`, `namespace` |
| `workshop.k8s.lease.guard.attempts` | counter | `lock.name`, `namespace` |
| `workshop.k8s.lease.guard.skipped` | counter | `lock.name`, `namespace`, `reason` |
| `workshop.k8s.lease.renew.attempts` | counter | `lock.name`, `namespace` |
| `workshop.k8s.lease.renew.failures` | counter | `lock.name`, `namespace`, `reason` |
| `workshop.k8s.lease.task.executions` | counter | `lock.name`, `namespace`, `result` |
| `workshop.k8s.lease.task.duration` | timer | `lock.name`, `namespace` |

`renew.*` meter는 선출된 작업이 실행 중임을 학습자가 볼 수 있도록 기록하는
workshop heartbeat입니다. 실제 owner-conditional Lease renewal은
`bluetape4k-leader-k8s` 내부에서 처리됩니다.

실제 coordinator를 활성화하면 `leader-micrometer`의 decorator meter도 함께 기록됩니다.

- `shedlock.leader.acquired`
- `shedlock.leader.not_acquired`
- `shedlock.leader.duration`
- `shedlock.leader.active`

## Run Locally

```bash
# Smoke-safe 기본 실행: Kubernetes 없이 시작하고 leader work를 skip합니다.
./gradlew :leader-k8s-lease-micrometer:bootRun

# 결정적 테스트: cluster와 kubeconfig가 필요 없습니다.
./gradlew :leader-k8s-lease-micrometer:test
```

metric은 Spring Boot Actuator로 노출됩니다.

```bash
curl -s http://localhost:8080/actuator/metrics/workshop.k8s.lease.guard.skipped
curl -s http://localhost:8080/actuator/prometheus | grep workshop_k8s_lease
```

## kind RBAC Example

실제 Kubernetes 경로를 켜기 전에 namespace, service account, Role, RoleBinding을
적용합니다.

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: workshop
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: lease-runner
  namespace: workshop
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: lease-runner
  namespace: workshop
rules:
  - apiGroups: ["coordination.k8s.io"]
    resources: ["leases"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: lease-runner
  namespace: workshop
subjects:
  - kind: ServiceAccount
    name: lease-runner
    namespace: workshop
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: lease-runner
```

실제 cluster로 실행합니다.

```bash
./gradlew :leader-k8s-lease-micrometer:bootRun \
  --args='--workshop.leader.k8s.enabled=true \
          --workshop.leader.k8s.namespace=workshop \
          --workshop.leader.k8s.identity=local-dev-1'
```

`identity`가 다른 인스턴스 두 개를 시작해 보세요. 한 번에 하나의 인스턴스만
`workshop.k8s.lease.task.executions{result="success"}`를 증가시켜야 합니다. 선출된
인스턴스를 중지하면 현재 lease가 만료된 뒤 다른 인스턴스가 같은 Lease를 획득합니다.

## Test Map

| Test class | 보호하는 동작 |
|------------|---------------|
| `K8sLeaseMicrometerPropertiesTest` | property 기본값, 검증, `KubernetesLeaseOptions` 변환 |
| `K8sLeaseMetricsTest` | 안정적인 meter 이름, tag, counter, timer, active gauge reset |
| `K8sLeaseGuardedTaskTest` | Kubernetes 없이 elected, skipped, task-failure 경로 검증 |
| `K8sLeaseMicrometerContextTest` | 기본 Spring context가 disabled coordinator를 사용하고 `KubernetesClient`를 만들지 않음 |

## Production Boundaries

이 모듈은 workshop이지 production operator가 아닙니다. 실제 서비스에는 pod
disruption budget, readiness gate, Kubernetes API retry policy, alerting, RBAC
검토, identity naming rule, graceful shutdown, guarded task의 업무별 idempotency가
추가로 필요합니다.

## Dependencies

```kotlin
implementation(libs.bluetape4k.leader.k8s)
implementation(libs.bluetape4k.leader.micrometer)
implementation(libs.fabric8.kubernetes.client)
implementation(libs.micrometer.core)
implementation(libs.micrometer.registry.prometheus)
implementation(libs.spring.boot.starter.actuator)
implementation(libs.spring.boot.starter.webmvc.lib)
testImplementation(libs.bluetape4k.assertions)
testImplementation(libs.kotlinx.coroutines.test.lib)
```
