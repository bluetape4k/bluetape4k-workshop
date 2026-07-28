package io.bluetape4k.workshop.graph.knowledge.schema

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
