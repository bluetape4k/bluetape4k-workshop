package io.bluetape4k.workshop.aws.bedrock

import io.bluetape4k.logging.KLogging
import io.bluetape4k.logging.info

private object BedrockConverseApplicationLogging : KLogging()

/**
 * 실제 AWS 호출은 기본 실행에서 제외하고, 명시적인 opt-in 경계만 안내합니다.
 */
fun main() {
    val mode = System.getProperty("bluetape4k.aws.bedrock.mode", "local")
    if (mode == "real-aws") {
        BedrockConverseApplicationLogging.log.info {
            "real-aws mode is enabled; construct BedrockRuntimeClient explicitly before invoking the sample."
        }
    } else {
        BedrockConverseApplicationLogging.log.info {
            "Bedrock workshop is credential-free by default; use -Dbluetape4k.aws.bedrock.mode=real-aws for live AWS."
        }
    }
}
