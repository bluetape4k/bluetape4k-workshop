package io.bluetape4k.workshop.graph.eventlineage.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

/**
 * 불변 비즈니스 이벤트의 정점 레이블입니다.
 *
 * ## 속성
 * - `eventId` 안정적인 비즈니스 이벤트 식별자입니다.
 * - `type` `OrderApproved` 같은 도메인 이벤트 유형입니다.
 * - `occurredAt` 백엔드 중립성을 위해 문자열로 저장하는 ISO-8601 타임스탬프입니다.
 * - `summary` 학습자가 읽을 수 있는 이벤트 설명입니다.
 */
object EventLabel : VertexLabel("Event") {
    val eventId = string("eventId")
    val type = string("type")
    val occurredAt = string("occurredAt")
    val summary = string("summary")
}

/**
 * 이벤트를 발행하는 aggregate 상태 스냅샷의 정점 레이블입니다.
 */
object AggregateLabel : VertexLabel("Aggregate") {
    val aggregateId = string("aggregateId")
    val aggregateType = string("aggregateType")
    val state = string("state")
    val version = string("version")
}

/**
 * 결정을 담당하는 사람 또는 시스템의 정점 레이블입니다.
 */
object ActorLabel : VertexLabel("Actor") {
    val actorId = string("actorId")
    val displayName = string("displayName")
    val role = string("role")
}

/**
 * 명시적인 승인 또는 검토 결정의 정점 레이블입니다.
 */
object DecisionLabel : VertexLabel("Decision") {
    val decisionId = string("decisionId")
    val decisionType = string("decisionType")
    val status = string("status")
    val reason = string("reason")
}

/**
 * aggregate 감사 스트림에 속한 이벤트를 기록하는 Aggregate -> Event 간선입니다.
 */
object EmitsLabel : EdgeLabel("EMITS", AggregateLabel, EventLabel)

/**
 * 인과 lineage를 기록하는 Event -> upstream Event 간선입니다.
 */
object CausedByLabel : EdgeLabel("CAUSED_BY", EventLabel, EventLabel)

/**
 * 승인 증거를 기록하는 Event -> Decision 간선입니다.
 */
object ApprovedByLabel : EdgeLabel("APPROVED_BY", EventLabel, DecisionLabel)

/**
 * 누가 또는 무엇이 결정을 내렸는지 기록하는 Decision -> Actor 간선입니다.
 */
object DecidedByLabel : EdgeLabel("DECIDED_BY", DecisionLabel, ActorLabel)

/**
 * 이벤트 정정 또는 대체를 기록하는 Event -> previous Event 간선입니다.
 */
object SupersedesLabel : EdgeLabel("SUPERSEDES", EventLabel, EventLabel)
