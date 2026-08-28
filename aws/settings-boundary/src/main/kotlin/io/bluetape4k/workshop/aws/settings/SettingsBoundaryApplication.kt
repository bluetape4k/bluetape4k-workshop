package io.bluetape4k.workshop.aws.settings

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info

private object SettingsBoundaryApplicationLogging : KLogging()

/**
 * AWS settings boundary의 credential-free 실행 진입점입니다.
 *
 * 실제 AWS client를 만들지 않고 source factory 주입과 local fake 테스트
 * 경계를 안내합니다. AWS credential을 읽는 작업은 명시적인 애플리케이션
 * 조합 코드가 담당합니다.
 */
fun main() {
    SettingsBoundaryApplicationLogging.log.info {
        "AWS settings boundary ready: use a SettingsSource factory; no credentials or network are resolved."
    }
}
