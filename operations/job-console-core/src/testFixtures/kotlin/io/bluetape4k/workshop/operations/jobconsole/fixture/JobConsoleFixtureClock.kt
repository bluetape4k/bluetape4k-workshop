package io.bluetape4k.workshop.operations.jobconsole.fixture

import io.bluetape4k.junit5.time.MutableClock
import java.time.Instant

/**
 * JobConsoleFixtureClock 테스트 묶음은 운영 작업 콘솔의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
class JobConsoleFixtureClock(
    initial: Instant = Instant.parse("2026-07-21T00:00:00Z"),
) : MutableClock(initial)
