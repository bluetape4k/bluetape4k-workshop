package io.bluetape4k.workshop.optimization.fieldservice.adapter.http

import io.bluetape4k.workshop.optimization.fieldservice.domain.ConstraintExplanation
import io.bluetape4k.workshop.optimization.fieldservice.domain.ConstraintReasonCode
import io.bluetape4k.workshop.optimization.fieldservice.domain.DatasetId
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventDigest
import io.bluetape4k.workshop.optimization.fieldservice.domain.EventKey
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceLimits
import io.bluetape4k.workshop.optimization.fieldservice.domain.FieldServiceScoreSummary
import io.bluetape4k.workshop.optimization.fieldservice.domain.InvalidFieldServiceInput
import io.bluetape4k.workshop.optimization.fieldservice.domain.PlanId
import io.bluetape4k.workshop.optimization.fieldservice.domain.ProviderRequestId
import io.bluetape4k.workshop.optimization.fieldservice.domain.VersionVector
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.concurrent.ConcurrentHashMap

/** #524 wire DTO와 분리된 Field Service 전용 callback envelope입니다. */
data class FieldServiceCallbackEnvelope(
    val provider: FieldServiceProvider,
    val eventId: EventKey,
    val planningRequestId: String,
    val providerRequestId: ProviderRequestId,
    val providerRevision: Long,
    val requestGeneration: Long,
    val planId: PlanId,
    val datasetId: DatasetId,
    val status: FieldServiceCallbackStatus,
    val scoreSummary: FieldServiceScoreSummary,
    val constraintExplanations: List<ConstraintExplanation> = emptyList(),
) {
    init {
        require(planningRequestId.isNotBlank()) { "planningRequestId must not be blank" }
        require(planningRequestId.length <= FieldServiceLimits.MAX_STRING_LENGTH) {
            "planningRequestId exceeds ${FieldServiceLimits.MAX_STRING_LENGTH} characters"
        }
        require(providerRevision >= 0L) { "providerRevision must be non-negative" }
        require(requestGeneration >= 0L) { "requestGeneration must be non-negative" }
        require(constraintExplanations.size <= FieldServiceLimits.MAX_EXPLANATIONS) {
            "too many constraint explanations"
        }
    }

    companion object {
        private val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule()).build()

        /** 중복 키, 길이, 숫자, 닫힌 score/reason 집합을 모두 검증해 envelope를 만듭니다. */
        fun parse(
            body: ByteArray,
            canonicalizer: FieldServiceCanonicalizer = FieldServiceCanonicalizer(),
        ): FieldServiceCallbackEnvelope {
            val canonical = canonicalizer.canonicalBytes(body)
            val node = try {
                mapper.readTree(canonical)
            } catch (failure: Exception) {
                throw InvalidFieldServiceInput("invalid callback envelope", failure)
            } ?: throw InvalidFieldServiceInput("callback envelope must not be empty")
            return fromNode(node)
        }

        private fun fromNode(node: JsonNode): FieldServiceCallbackEnvelope {
            requireObjectKeys(node, ENVELOPE_FIELDS, "callback envelope")
            return FieldServiceCallbackEnvelope(
                provider = FieldServiceProvider.fromWire(node.requiredString("provider")),
                eventId = EventKey(node.requiredString("eventId")),
                planningRequestId = node.requiredString("planningRequestId"),
                providerRequestId = ProviderRequestId(node.requiredString("providerRequestId")),
                providerRevision = node.requiredLong("providerRevision"),
                requestGeneration = node.requiredLong("requestGeneration"),
                planId = PlanId(node.requiredString("planId")),
                datasetId = DatasetId(node.requiredString("datasetId")),
                status = node.requiredEnum("status", FieldServiceCallbackStatus::valueOf),
                scoreSummary = node.requiredScoreSummary("scoreSummary"),
                constraintExplanations = node.requiredExplanations("constraintExplanations"),
            )
        }

        private val ENVELOPE_FIELDS = setOf(
            "provider",
            "eventId",
            "planningRequestId",
            "providerRequestId",
            "providerRevision",
            "requestGeneration",
            "planId",
            "datasetId",
            "status",
            "scoreSummary",
            "constraintExplanations",
        )

        private val SCORE_FIELDS = setOf("hardScore", "softScore", "assignedCount", "unassignedCount")
        private val EXPLANATION_FIELDS = setOf("visitId", "reason")

        private fun requireObjectKeys(node: JsonNode, allowed: Set<String>, label: String) {
            if (!node.isObject) throw InvalidFieldServiceInput("$label must be an object")
            val actual = node.properties().asSequence().map { it.key }.toSet()
            if (actual != allowed) {
                val unknown = (actual - allowed).sorted().joinToString(",")
                val missing = (allowed - actual).sorted().joinToString(",")
                throw InvalidFieldServiceInput("$label fields are not closed: unknown=$unknown missing=$missing")
            }
        }

        private fun JsonNode.requiredString(field: String): String {
            val value = this[field] ?: throw InvalidFieldServiceInput("missing callback field: $field")
            if (!value.isString) throw InvalidFieldServiceInput("callback field must be a string: $field")
            return value.stringValue()
        }

        private fun JsonNode.requiredLong(field: String): Long {
            val value = this[field] ?: throw InvalidFieldServiceInput("missing callback field: $field")
            if (!value.isNumber) throw InvalidFieldServiceInput("callback field must be a finite integer: $field")
            val decimal = try {
                value.decimalValue()
            } catch (failure: Exception) {
                throw InvalidFieldServiceInput("callback field must be a finite integer: $field", failure)
            }
            if (decimal.stripTrailingZeros().scale() > 0) {
                throw InvalidFieldServiceInput("callback field must be an integer: $field")
            }
            return try {
                decimal.longValueExact()
            } catch (failure: ArithmeticException) {
                throw InvalidFieldServiceInput("callback integer is out of range: $field", failure)
            }
        }

        private fun <T> JsonNode.requiredEnum(field: String, parser: (String) -> T): T = try {
            parser(requiredString(field))
        } catch (failure: IllegalArgumentException) {
            throw InvalidFieldServiceInput("unknown callback value: $field", failure)
        }

        private fun JsonNode.requiredScoreSummary(field: String): FieldServiceScoreSummary {
            val value = this[field] ?: throw InvalidFieldServiceInput("missing callback field: $field")
            requireObjectKeys(value, SCORE_FIELDS, field)
            val assigned = value.requiredLong("assignedCount")
            val unassigned = value.requiredLong("unassignedCount")
            if (assigned < 0L || unassigned < 0L || assigned > Int.MAX_VALUE || unassigned > Int.MAX_VALUE) {
                throw InvalidFieldServiceInput("score counts are out of range")
            }
            return FieldServiceScoreSummary(
                hardScore = value.requiredLong("hardScore"),
                softScore = value.requiredLong("softScore"),
                assignedCount = assigned.toInt(),
                unassignedCount = unassigned.toInt(),
            )
        }

        private fun JsonNode.requiredExplanations(field: String): List<ConstraintExplanation> {
            val value = this[field] ?: throw InvalidFieldServiceInput("missing callback field: $field")
            if (!value.isArray) throw InvalidFieldServiceInput("callback field must be an array: $field")
            val explanations = value.iterator().asSequence().map { item ->
                requireObjectKeys(item, EXPLANATION_FIELDS, "constraint explanation")
                ConstraintExplanation(
                    visitId = io.bluetape4k.workshop.optimization.fieldservice.domain.VisitId(item.requiredString("visitId")),
                    reason = item.requiredEnum("reason", ConstraintReasonCode::valueOf),
                )
            }.toList()
            if (explanations.size > FieldServiceLimits.MAX_EXPLANATIONS) {
                throw InvalidFieldServiceInput("too many constraint explanations")
            }
            return explanations
        }
    }
}

/** local Field Service seam에서만 사용하는 provider namespace입니다. */
enum class FieldServiceProvider {
    FAKE,
    TIMEFOLD_PLATFORM,
    CUSTOM_SOLVER,
    UNKNOWN;

    companion object {
        fun fromWire(value: String): FieldServiceProvider =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/** provider fixture가 반환하는 callback state입니다. */
enum class FieldServiceCallbackStatus {
    SUCCEEDED,
    FAILED,
    REJECTED,
}

/** preflight 결과이며 stale 결과만 local audit evidence로 보존합니다. */
enum class FieldServiceCallbackDecision {
    ACCEPTED,
    DUPLICATE,
    EVENT_KEY_REUSED,
    STALE_REVISION,
    STALE_REQUEST_GENERATION,
    UNKNOWN_PROVIDER,
    PROVIDER_MISMATCH,
    PROVIDER_REQUEST_MISMATCH,
    PLANNING_REQUEST_MISMATCH,
    REQUEST_GENERATION_MISMATCH,
    PLAN_MISMATCH,
    DATASET_MISMATCH,
    REJECTED,
    UNSIGNED,
    INVALID_SIGNATURE,
    INVALID_ENVELOPE,
}

/** local plan을 optional #524 seam에 제출할 때 캡처하는 binding입니다. */
data class FieldServiceCallbackBinding(
    val provider: FieldServiceProvider,
    val planningRequestId: String,
    val providerRequestId: ProviderRequestId,
    val requestGeneration: Long,
    val planId: PlanId,
    val datasetId: DatasetId,
    val versionVector: VersionVector,
) {
    init {
        require(planningRequestId.isNotBlank()) { "planningRequestId must not be blank" }
        require(requestGeneration >= 0L) { "requestGeneration must be non-negative" }
    }
}

/** callback preflight 결과이며 accepted일 때 단일 local state write 결과를 포함합니다. */
data class FieldServiceCallbackResult(
    val decision: FieldServiceCallbackDecision,
    val stateChanged: Boolean = false,
    val auditOnly: Boolean = false,
)

/** 최소 state port로 callback 인증을 provider HTTP와 독립시킵니다. */
interface FieldServiceCallbackState {
    fun register(binding: FieldServiceCallbackBinding)
    fun binding(planId: PlanId): FieldServiceCallbackBinding?
    fun eventDigest(eventId: EventKey): EventDigest?
    fun latestRevision(provider: FieldServiceProvider, providerRequestId: ProviderRequestId): Long?
    fun accept(envelope: FieldServiceCallbackEnvelope, digest: EventDigest)
    fun audit(decision: FieldServiceCallbackDecision, envelope: FieldServiceCallbackEnvelope)
}

/** synthetic fixture 경로와 unit test에서 사용하는 deterministic in-memory callback state입니다. */
class InMemoryFieldServiceCallbackState : FieldServiceCallbackState {
    private val bindings = ConcurrentHashMap<PlanId, FieldServiceCallbackBinding>()
    private val events = ConcurrentHashMap<EventKey, EventDigest>()
    private val revisions = ConcurrentHashMap<Pair<FieldServiceProvider, ProviderRequestId>, Long>()
    private val accepted = ConcurrentHashMap.newKeySet<EventKey>()
    private val audits = ConcurrentHashMap<FieldServiceCallbackDecision, Int>()

    override fun register(binding: FieldServiceCallbackBinding) {
        bindings[binding.planId] = binding
    }

    override fun binding(planId: PlanId): FieldServiceCallbackBinding? = bindings[planId]

    override fun eventDigest(eventId: EventKey): EventDigest? = events[eventId]

    override fun latestRevision(
        provider: FieldServiceProvider,
        providerRequestId: ProviderRequestId,
    ): Long? = revisions[provider to providerRequestId]

    override fun accept(envelope: FieldServiceCallbackEnvelope, digest: EventDigest) {
        events[envelope.eventId] = digest
        accepted += envelope.eventId
        revisions.merge(providerKey(envelope), envelope.providerRevision, ::maxOf)
    }

    override fun audit(decision: FieldServiceCallbackDecision, envelope: FieldServiceCallbackEnvelope) {
        audits.merge(decision, 1, Int::plus)
    }

    fun acceptedCount(): Int = accepted.size

    fun auditCount(decision: FieldServiceCallbackDecision): Int = audits[decision] ?: 0

    private fun providerKey(envelope: FieldServiceCallbackEnvelope) =
        envelope.provider to envelope.providerRequestId
}
