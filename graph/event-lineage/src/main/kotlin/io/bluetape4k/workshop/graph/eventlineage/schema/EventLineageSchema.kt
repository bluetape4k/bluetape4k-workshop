package io.bluetape4k.workshop.graph.eventlineage.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

/**
 * Vertex label for immutable business events.
 *
 * ## Properties
 * - `eventId` stable business event identifier.
 * - `type` domain event type, such as `OrderApproved`.
 * - `occurredAt` ISO-8601 timestamp stored as a string for backend neutrality.
 * - `summary` learner-readable explanation of the event.
 */
object EventLabel : VertexLabel("Event") {
    val eventId = string("eventId")
    val type = string("type")
    val occurredAt = string("occurredAt")
    val summary = string("summary")
}

/**
 * Vertex label for aggregate state snapshots that emit events.
 */
object AggregateLabel : VertexLabel("Aggregate") {
    val aggregateId = string("aggregateId")
    val aggregateType = string("aggregateType")
    val state = string("state")
    val version = string("version")
}

/**
 * Vertex label for humans or systems responsible for decisions.
 */
object ActorLabel : VertexLabel("Actor") {
    val actorId = string("actorId")
    val displayName = string("displayName")
    val role = string("role")
}

/**
 * Vertex label for explicit approval or review decisions.
 */
object DecisionLabel : VertexLabel("Decision") {
    val decisionId = string("decisionId")
    val decisionType = string("decisionType")
    val status = string("status")
    val reason = string("reason")
}

/**
 * Aggregate -> Event edge recording which events belong to an aggregate audit stream.
 */
object EmitsLabel : EdgeLabel("EMITS", AggregateLabel, EventLabel)

/**
 * Event -> upstream Event edge recording causal lineage.
 */
object CausedByLabel : EdgeLabel("CAUSED_BY", EventLabel, EventLabel)

/**
 * Event -> Decision edge recording approval evidence.
 */
object ApprovedByLabel : EdgeLabel("APPROVED_BY", EventLabel, DecisionLabel)

/**
 * Decision -> Actor edge recording who or what made a decision.
 */
object DecidedByLabel : EdgeLabel("DECIDED_BY", DecisionLabel, ActorLabel)

/**
 * Event -> previous Event edge recording event correction or replacement.
 */
object SupersedesLabel : EdgeLabel("SUPERSEDES", EventLabel, EventLabel)
