# graph-event-lineage

[English](README.md) | 한국어

## 아키텍처

이 모듈은 `bluetape4k-graph`와 in-memory TinkerGraph로 비즈니스 이벤트 lineage를
학습하는 예제입니다. aggregate 상태, domain event, approval decision, actor를 graph
vertex로 모델링하고, 현재 aggregate 상태를 설명하는 audit trail을 graph traversal로
재구성합니다.

> **관련 이슈:** [bluetape4k-workshop #330](https://github.com/bluetape4k/bluetape4k-workshop/issues/330)

![Event Lineage Architecture](../../docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.png)

SVG source: [graph-event-lineage-readme-architecture-01.svg](../../docs/images/readme-diagrams/graph-event-lineage-readme-architecture-01.svg)

## 개요

이 예제의 질문은 단순합니다. row 단위 audit table보다 graph가 audit trail을 더 잘
설명하는 순간은 언제인가?

주요 학습 내용:

- `Event`, `Aggregate`, `Decision`, `Actor` vertex를 표현합니다.
- `EMITS`, `CAUSED_BY`, `APPROVED_BY`, `DECIDED_BY`, `SUPERSEDES` edge로 관계를 연결합니다.
- graph 구조에서 결정적인 aggregate audit trail을 재구성합니다.
- 현재 event에서 root event까지 bounded causal path를 따라갑니다.
- causal 또는 superseding evidence가 빠진 emitted event를 찾습니다.
- TinkerGraph만 사용해 컨테이너 없이 workshop 테스트를 실행합니다.

![Event Lineage Audit Sequence](../../docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.png)

SVG source: [graph-event-lineage-readme-sequence-01.svg](../../docs/images/readme-diagrams/graph-event-lineage-readme-sequence-01.svg)

## Graph Lineage와 Audit Table의 차이

| 방식 | 잘하는 일 | tradeoff |
|---|---|---|
| 일반 audit table | field-level before/after 값을 시간순으로 기록 | aggregate나 decision을 넘나드는 "무엇이 이 상태를 만들었나" 질문에는 약합니다 |
| JaVers 스타일 object history | object snapshot과 property diff 설명 | persistence history에는 좋지만 cross-event causality graph 자체는 아닙니다 |
| Event lineage graph | cause, approval, superseding, impact path 설명 | 관계를 명시적으로 모델링하고 traversal bound를 정해야 합니다 |

상태가 왜 존재하는지, 어떤 upstream event가 원인인지, 어떤 decision이 승인했는지,
어떤 newer event가 older event를 대체했는지를 설명하려면 이 모듈의 graph lineage
방식이 잘 맞습니다. object history, persistence snapshot, field-level diff가 학습
목표라면 일반 audit table이나 JaVers 예제가 더 적합합니다.

## Domain Model

### Vertices

| Label | Key property | Other properties | 의미 |
|---|---|---|---|
| `Event` | `eventId` | `type`, `occurredAt`, `summary` | 불변 비즈니스 사실 |
| `Aggregate` | `aggregateId` | `aggregateType`, `state`, `version` | 설명하려는 현재 aggregate 상태 |
| `Decision` | `decisionId` | `decisionType`, `status`, `reason` | 승인 또는 검토 결과 |
| `Actor` | `actorId` | `displayName`, `role` | decision을 만든 사람이나 시스템 |

### Edges

| Label | From | To | 답하는 질문 |
|---|---|---|---|
| `EMITS` | `Aggregate` | `Event` | 이 aggregate audit stream에 속한 event는 무엇인가? |
| `CAUSED_BY` | `Event` | upstream `Event` | 이 event를 일으킨 이전 event는 무엇인가? |
| `APPROVED_BY` | `Event` | `Decision` | 이 상태 전이를 승인한 decision은 무엇인가? |
| `DECIDED_BY` | `Decision` | `Actor` | 누가 또는 어떤 시스템이 decision을 만들었는가? |
| `SUPERSEDES` | `Event` | previous `Event` | 어떤 newer event가 older event를 교정하거나 대체했는가? |

## Core Queries

| Method | 설명 |
|---|---|
| `eventsForAggregate(aggregateId)` | emitted event를 `occurredAt`, `eventId` 순서로 반환합니다. |
| `causalPath(eventId, rootEventId, maxDepth)` | `CAUSED_BY` edge를 따라 current event에서 root event까지 bounded traversal을 수행합니다. |
| `auditTrailForAggregate(aggregateId)` | aggregate 상태, emitted event, root cause, approval evidence를 재구성합니다. |
| `supersededChain(eventId)` | newest event에서 previous event로 `SUPERSEDES` chain을 따라갑니다. |
| `missingCausalLinks(aggregateId)` | root-cause, causal, superseding evidence가 없는 emitted event를 찾습니다. |

알 수 없는 ID는 빈 결과를 반환합니다. blank ID는 bluetape4k validation helper로
즉시 실패합니다.

## 사용 예

```kotlin
import io.bluetape4k.graph.tinkerpop.TinkerGraphOperations
import io.bluetape4k.workshop.graph.eventlineage.service.EventLineageService

TinkerGraphOperations().use { ops ->
    val service = EventLineageService(ops, graphName = "order_lineage")
    service.initialize()

    val order = service.addAggregate("order-1001", "Order", "APPROVED", version = 4)
    val created = service.addEvent(
        eventId = "order-created",
        type = "OrderCreated",
        occurredAt = "2026-07-02T01:00:00Z",
        summary = "Customer submitted the order.",
    )
    val approved = service.addEvent(
        eventId = "order-approved",
        type = "OrderApproved",
        occurredAt = "2026-07-02T01:04:00Z",
        summary = "Order moved to approved state.",
    )

    service.emit(order.id, created.id)
    service.emit(order.id, approved.id)
    service.causedBy(approved.id, created.id)

    val path = service.causalPath("order-approved", "order-created")
    val trail = service.auditTrailForAggregate("order-1001")

    println(path.nodes.map { it.nodeId })
    println(trail.events.map { it.nodeId })
}
```

## 테스트 실행

```bash
./gradlew :graph-event-lineage:test
```

테스트 경로는 TinkerGraph만 사용합니다. Docker, Testcontainers, Neo4j, Memgraph,
PostgreSQL, JaVers persistence가 필요하지 않습니다.

## 의존성

```kotlin
dependencies {
    implementation(libs.bluetape4k.graph.core)
    implementation(libs.bluetape4k.graph.tinkerpop)
    implementation(libs.bluetape4k.logging)
}
```

bluetape4k 버전은 repository 수준의 `bluetape4k-dependencies` platform이 관리합니다.
이 consumer workshop module은 version 없는 alias만 선언하며, 개별 graph BOM을 import하지 않습니다.

## Package Structure

```text
io.bluetape4k.workshop.graph.eventlineage
├── model
│   └── AuditTrail.kt           - LineageNode, LineagePath, ApprovalEvidence, AggregateAuditTrail
├── schema
│   └── EventLineageSchema.kt   - Event, Aggregate, Actor, Decision labels and lineage edges
└── service
    └── EventLineageService.kt  - GraphOperations-based mutation and audit queries
```

## 관련 문서

- [bluetape4k-graph](https://github.com/bluetape4k/bluetape4k-graph) - graph library source
- [graph-io-pipeline](../io-pipeline/README.ko.md) - import/export adapter 예제
- [graph-knowledge-graph](../knowledge-graph/README.ko.md) - heterogeneous graph model 예제
- [exposed-javers-approval-workflow](../../exposed/javers-approval-workflow/README.ko.md) - JaVers approval history 예제
- [bluetape4k-workshop #330](https://github.com/bluetape4k/bluetape4k-workshop/issues/330) - tracking issue
