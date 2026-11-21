package io.bluetape4k.workshop.operations.jobconsole.application

import io.bluetape4k.support.requireNotNull
import io.bluetape4k.utils.Resourcex

/**
 * JobConsoleUi은 운영 작업 콘솔 실무 예제의 업무 경계를 표현하는 타입입니다. 입력 검증, 상태 전이, 저장·발행 계약을 이 타입의 규칙에 맞춰 해석합니다.
 *
 * 이 설명은 입력 검증, 권한·멱등성, 상태 전이, 영속화와 이벤트 발행 사이의 업무 계약을 함께 이해하기 위한 것입니다.
 */
object JobConsoleUi {
    /**
     * 운영 작업 콘솔 클래스가 유지하는 업무 상태입니다. 변경 시 관련 검증·상태 전이·동시성 규칙을 함께 적용합니다.
     */
    val indexHtml: String by lazy {
        Resourcex.getInputStream("static/job-console/index.html")
            .requireNotNull { "job console UI resource is missing" }
            .bufferedReader()
            .use { it.readText() }
    }
}
