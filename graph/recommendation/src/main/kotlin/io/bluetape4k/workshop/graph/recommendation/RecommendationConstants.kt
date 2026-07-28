package io.bluetape4k.workshop.graph.recommendation

/** 모든 recommend* 함수의 [limit] 인자에 적용하는 상한입니다. */
const val MAX_RECOMMENDATION_LIMIT: Int = 100

/** recommend* 함수에 명시적인 값이 전달되지 않았을 때 사용하는 기본 [limit]입니다. */
const val DEFAULT_RECOMMENDATION_LIMIT: Int = 10
