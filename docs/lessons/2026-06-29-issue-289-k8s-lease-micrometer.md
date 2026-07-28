# Issue 289 Kubernetes Lease Micrometer lesson

## 배경

Issue #289는 Micrometer metric을 포함한 Kubernetes Lease leader election Spring Boot 4
workshop example을 추가했다.

## 결정

example은 `KubernetesLeaseSuspendLeaderElector`에 direct Spring configuration을 사용하고,
real Kubernetes access는 `workshop.leader.k8s.enabled=true` 뒤에 둔다.

## 결과

default test는 Kubernetes cluster 없이 deterministic하게 유지된다. annotation/AOP starter
path는 이 example에 불필요하고 optional AOP/reactive classpath requirement를 smoke test로
끌어들일 수 있으므로 사용하지 않았다.

## 향후 지침

leader-election workshop example에서는 local smoke path를 disabled-by-default로 유지하고,
RBAC와 opt-in runtime command를 문서화한다. example이 실제로 annotation/AOP integration
path를 가르칠 때만 `leader-spring-boot`를 추가한다.
