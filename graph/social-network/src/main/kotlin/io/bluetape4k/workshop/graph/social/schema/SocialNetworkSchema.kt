package io.bluetape4k.workshop.graph.social.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

// ────────────────────────────────────────────────────────────────────────────
// 정점 label
// ────────────────────────────────────────────────────────────────────────────

/**
 * social network의 Person node를 나타내는 정점 label입니다.
 *
 * ## 속성
 * - `personId` - 안정적인 도메인 키입니다. username이나 UUID처럼 내부 구조를 드러내지 않는 문자열입니다.
 * - `name` - 표시 이름입니다.
 * - `title` - 직무 title입니다. 선택 값입니다.
 * - `location` - 도시 또는 지역입니다. 선택 값입니다.
 */
object PersonLabel : VertexLabel("Person") {
    val personId = string("personId")
    val name = string("name")
    val title = string("title")
    val location = string("location")
}

/**
 * social network의 Company node를 나타내는 정점 label입니다.
 *
 * ## 속성
 * - `companyId` - 안정적인 도메인 키입니다. 내부 구조를 드러내지 않는 문자열입니다.
 * - `name` - 회사 표시 이름입니다.
 * - `industry` - 산업 분야입니다. 선택 값입니다.
 * - `location` - 본사 도시 또는 지역입니다. 선택 값입니다.
 */
object CompanyLabel : VertexLabel("Company") {
    val companyId = string("companyId")
    val name = string("name")
    val industry = string("industry")
    val location = string("location")
}

// ────────────────────────────────────────────────────────────────────────────
// 간선 label
// ────────────────────────────────────────────────────────────────────────────

/**
 * 두 Person 정점 사이의 양방향 acquaintance 간선입니다.
 *
 * **중요**: 동일한 속성을 가진 두 개의 방향성 간선(A -> B, B -> A)으로 저장합니다.
 * [io.bluetape4k.workshop.graph.social.service.SocialNetworkService.connect]는 pair마다 한 번만 호출하고,
 * 인자를 뒤집어 다시 호출하지 않습니다.
 *
 * ## 속성
 * - `since` - connection이 성립된 ISO-8601 날짜입니다. 선택 값입니다.
 * - `strength` - connection 강도입니다. 범위는 1-10이고 `"8"` 같은 문자열로 저장합니다.
 */
object KnowsLabel : EdgeLabel("KNOWS", PersonLabel, PersonLabel) {
    val since = string("since")
    val strength = string("strength")   // stored as String, valid values "1".."10"
}

/**
 * Person에서 Company로 향하는 재직 간선입니다.
 *
 * ## 속성
 * - `role` - 직무 title 또는 role 이름입니다. 필수이며 blank이면 안 됩니다.
 * - `startDate` - ISO-8601 재직 시작일입니다. 선택 값입니다.
 * - `isCurrent` - 현재 재직 여부를 나타내는 `"true"` 또는 `"false"` 문자열입니다.
 */
object WorksAtLabel : EdgeLabel("WORKS_AT", PersonLabel, CompanyLabel) {
    val role = string("role")
    val startDate = string("startDate")
    val isCurrent = string("isCurrent")  // stored as String "true"/"false"
}

/**
 * 한 Person에서 다른 Person으로 향하는 단방향 follower 간선입니다.
 *
 * [KnowsLabel]과 달리 `FOLLOWS`는 단방향이며 간선 하나만 만듭니다.
 * `FOLLOWS` 관계는 상호 acquaintance를 뜻하지 않습니다.
 */
object FollowsLabel : EdgeLabel("FOLLOWS", PersonLabel, PersonLabel)
