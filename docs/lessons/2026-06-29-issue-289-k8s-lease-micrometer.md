# Issue 289 Kubernetes Lease Micrometer lesson

## Context

Issue #289 added a Spring Boot 4 workshop example for Kubernetes Lease leader election with Micrometer metrics.

## Decision

The example uses direct Spring configuration for `KubernetesLeaseSuspendLeaderElector` and keeps real Kubernetes access behind `workshop.leader.k8s.enabled=true`.

## Outcome

Default tests stay deterministic without a Kubernetes cluster. The annotation/AOP starter path was not used because it is unnecessary for this example and can pull optional AOP/reactive classpath requirements into smoke tests.

## Future Guidance

For leader-election workshop examples, keep the local smoke path disabled-by-default, document RBAC and opt-in runtime commands, and only add `leader-spring-boot` when the example actually teaches the annotation/AOP integration path.
