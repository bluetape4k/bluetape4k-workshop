package io.bluetape4k.workshop.graph.knowledge.schema

import io.bluetape4k.graph.model.GraphConstraint
import io.bluetape4k.graph.model.GraphConstraintType
import io.bluetape4k.graph.model.GraphIndex
import io.bluetape4k.graph.model.GraphSchemaEntityType
import io.bluetape4k.graph.schema.GraphSchemaDefinition
import io.bluetape4k.graph.schema.GraphSchemaNames
import io.bluetape4k.graph.schema.GraphSchemaPlan
import io.bluetape4k.graph.schema.GraphSchemaPlanAction
import io.bluetape4k.graph.schema.GraphSchemaPlanItem
import io.bluetape4k.graph.schema.GraphSchemaPlanOptions
import io.bluetape4k.graph.schema.GraphSuspendSchemaManager
import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

// ────────────────────────────────────────────────────────────────────────────
// 정점 label
// ────────────────────────────────────────────────────────────────────────────

/**
 * 사람, 조직, 장소, 제품 같은 현실 세계 객체를 나타내는 Entity 정점입니다.
 *
 * ## 속성
 * - `entityId` - 안정적인 도메인 키입니다. slug나 UUID처럼 내부 구조를 드러내지 않는 문자열입니다.
 * - `name` - 사람이 읽을 수 있는 표시 이름입니다.
 * - `entityType` - `"Language"`, `"Framework"`, `"Person"` 같은 자유 형식 분류입니다.
 */
object EntityLabel : VertexLabel("Entity") {
    val entityId = string("entityId")
    val name = string("name")
    val entityType = string("entityType")
}

/**
 * 정규화된 도메인 어휘 항목을 나타내는 Concept 정점입니다.
 *
 * ## 속성
 * - `conceptId` - 안정적인 도메인 키입니다.
 * - `name` - Concept의 표시 이름입니다.
 * - `domain` - `"software"`, `"science"` 같은 주제 영역입니다.
 */
object ConceptLabel : VertexLabel("Concept") {
    val conceptId = string("conceptId")
    val name = string("name")
    val domain = string("domain")
}

/**
 * Entity를 언급하는 원천 문서를 나타내는 Document 정점입니다.
 *
 * ## 속성
 * - `documentId` - 안정적인 도메인 키입니다.
 * - `title` - 문서 제목입니다.
 * - `source` - 원천 시스템 또는 URL입니다. 선택 값입니다.
 */
object DocumentLabel : VertexLabel("Document") {
    val documentId = string("documentId")
    val title = string("title")
    val source = string("source")
}

// ────────────────────────────────────────────────────────────────────────────
// 간선 label
// ────────────────────────────────────────────────────────────────────────────

/**
 * 문서가 해당 Entity를 언급한다는 뜻을 나타내는 Document -> Entity 간선입니다.
 *
 * ## 속성
 * - `confidence` - 추출 신뢰도 점수입니다. 범위는 0-100입니다.
 */
object MentionsLabel : EdgeLabel("MENTIONS", DocumentLabel, EntityLabel) {
    val confidence = integer("confidence")
}

/**
 * 두 Entity 정점 사이의 의미 관계를 나타내는 방향성 간선입니다.
 *
 * ## 속성
 * - `relationType` - `"has-feature"`, `"integrates-with"` 같은 관계 종류를 설명합니다.
 */
object RelatedToLabel : EdgeLabel("RELATED_TO", EntityLabel, EntityLabel) {
    val relationType = string("relationType")
}

/**
 * Entity가 해당 Concept 아래로 분류된다는 뜻을 나타내는 Entity -> Concept 간선입니다.
 *
 * 방향: Entity -> Concept(`IS_A` 분류)입니다.
 */
object IsALabel : EdgeLabel("IS_A", EntityLabel, ConceptLabel)

/**
 * knowledge graph가 기대하는 backend-neutral schema 선언입니다.
 *
 * Entity/Concept/Document의 도메인 키에는 lookup index와 UNIQUE constraint를 함께
 * 선언합니다. 실제 DDL은 서비스가 자동으로 적용하지 않고, 호출자가 반환된
 * [io.bluetape4k.graph.schema.GraphSchemaPlan]을 명시적으로 적용해야 합니다.
 */
object KnowledgeGraphSchema {

    private val keyedLabels = listOf(
        EntityLabel.label to EntityLabel.entityId.name,
        ConceptLabel.label to ConceptLabel.conceptId.name,
        DocumentLabel.label to DocumentLabel.documentId.name,
    )

    /** desired index/constraint definition을 매 호출마다 새 immutable 객체로 반환합니다. */
    fun desiredSchema(): GraphSchemaDefinition =
        GraphSchemaDefinition(
            indexes = keyedLabels.mapTo(linkedSetOf()) { (label, property) ->
                GraphIndex(
                    name = GraphSchemaNames.indexName(label, property),
                    label = label,
                    property = property,
                    entityType = GraphSchemaEntityType.VERTEX,
                )
            },
            constraints = keyedLabels.mapTo(linkedSetOf()) { (label, property) ->
                GraphConstraint(
                    name = GraphSchemaNames.uniqueConstraintName(label, property),
                    label = label,
                    property = property,
                    type = GraphConstraintType.UNIQUE,
                    entityType = GraphSchemaEntityType.VERTEX,
                )
            },
        )
}

/**
 * suspend schema manager에서 live metadata를 읽어 blocking planner와 같은 계획 모델을
 * 반환합니다. upstream 2.0.0 planner가 blocking manager API로 제공되므로, coroutine
 * backend에서는 동일한 semantic 비교를 non-blocking metadata 호출로 수행합니다.
 */
suspend fun GraphSuspendSchemaManager.plan(
    desired: GraphSchemaDefinition,
    options: GraphSchemaPlanOptions = GraphSchemaPlanOptions(),
): GraphSchemaPlan {
    val liveIndexes = listIndexes()
    val liveConstraints = listConstraints()
    val desiredIndexes = desired.indexes
    val desiredConstraints = desired.constraints
    val items = buildList {
        desiredIndexes.sortedWith(schemaObjectComparator()).filterNot { wanted ->
            liveIndexes.any { it.sameSchema(wanted) }
        }.forEach {
            add(
                GraphSchemaPlanItem(
                    action = GraphSchemaPlanAction.CREATE_INDEX,
                    index = it,
                    reason = "desired index is missing",
                ),
            )
        }
        desiredConstraints.sortedWith(schemaObjectComparator()).filterNot { wanted ->
            liveConstraints.any { it.sameSchema(wanted) }
        }.forEach {
            add(
                GraphSchemaPlanItem(
                    action = GraphSchemaPlanAction.CREATE_CONSTRAINT,
                    constraint = it,
                    reason = "desired unique constraint is missing",
                ),
            )
        }
        liveIndexes.sortedWith(schemaObjectComparator()).filterNot { existing ->
            desiredIndexes.any { it.sameSchema(existing) }
        }.forEach {
            add(
                GraphSchemaPlanItem(
                    action = if (options.allowDestructiveDrops) {
                        GraphSchemaPlanAction.DROP_INDEX
                    } else {
                        GraphSchemaPlanAction.SKIP
                    },
                    index = it,
                    reason = if (options.allowDestructiveDrops) {
                        "live index is extra"
                    } else {
                        "destructive drop is disabled"
                    },
                ),
            )
        }
        liveConstraints.sortedWith(schemaObjectComparator()).filterNot { existing ->
            desiredConstraints.any { it.sameSchema(existing) }
        }.forEach {
            add(
                GraphSchemaPlanItem(
                    action = if (options.allowDestructiveDrops) {
                        GraphSchemaPlanAction.UNSUPPORTED
                    } else {
                        GraphSchemaPlanAction.SKIP
                    },
                    constraint = it,
                    reason = if (options.allowDestructiveDrops) {
                        "GraphSchemaManager does not expose constraint drop"
                    } else {
                        "destructive drop is disabled"
                    },
                ),
            )
        }
    }
    return GraphSchemaPlan(items, options)
}

private fun GraphIndex.sameSchema(other: GraphIndex): Boolean =
    label == other.label && property == other.property && entityType == other.entityType && unique == other.unique

private fun GraphConstraint.sameSchema(other: GraphConstraint): Boolean =
    label == other.label && property == other.property && entityType == other.entityType && type == other.type

private fun <T> schemaObjectComparator(): Comparator<T> = compareBy { it.toString() }
