package io.bluetape4k.workshop.graph.recommendation.schema

import io.bluetape4k.graph.schema.EdgeLabel
import io.bluetape4k.graph.schema.VertexLabel

// ────────────────────────────────────────────────────────────────────────────
// 정점 label
// ────────────────────────────────────────────────────────────────────────────

/**
 * 추천 그래프의 User node를 나타내는 정점 label입니다.
 *
 * ## 속성
 * - `userId` - 안정적인 도메인 키입니다. username이나 UUID처럼 내부 구조를 드러내지 않는 문자열입니다.
 * - `name` - 표시 이름입니다.
 */
object UserLabel : VertexLabel("User") {
    val userId = string("userId")
    val name = string("name")
}

/**
 * 추천 그래프의 Product node를 나타내는 정점 label입니다.
 *
 * ## 속성
 * - `productId` - 안정적인 도메인 키입니다. SKU나 UUID처럼 내부 구조를 드러내지 않는 문자열입니다.
 * - `name` - 표시 이름입니다.
 * - `category` - 상품 category입니다. 선택 값입니다.
 */
object ProductLabel : VertexLabel("Product") {
    val productId = string("productId")
    val name = string("name")
    val category = string("category")
}

// ────────────────────────────────────────────────────────────────────────────
// 간선 label
// ────────────────────────────────────────────────────────────────────────────

/**
 * User 정점에서 Product 정점으로 향하는 구매 간선입니다.
 *
 * 같은 User에서 같은 Product로 향하는 `PURCHASED` 간선 여러 개를 허용합니다.
 * 멱등을 보장하지 않으며, [purchase][io.bluetape4k.workshop.graph.recommendation.service.RecommendationService.purchase]
 * 호출마다 새 간선을 만듭니다. 중복 제거는 추천 알고리즘 수준에서 처리합니다.
 *
 * ## 속성
 * - `rating` - 문자열로 저장하는 구매 평점 `"1"`-`"5"`입니다. 평가가 없으면 저장하지 않습니다.
 * - `purchasedAt` - ISO-8601 타임스탬프입니다. 설정하지 않으면 저장하지 않습니다.
 */
object PurchasedLabel : EdgeLabel("PURCHASED", UserLabel, ProductLabel) {
    val rating = string("rating")
    val purchasedAt = string("purchasedAt")
}

/**
 * 한 User 정점에서 다른 User 정점으로 향하는 단방향 follow 간선입니다.
 *
 * 양방향 연결과 달리 `FOLLOWS`는 단방향입니다.
 * follower/followee 쌍마다 간선 하나만 만듭니다.
 */
object FollowsLabel : EdgeLabel("FOLLOWS", UserLabel, UserLabel)
