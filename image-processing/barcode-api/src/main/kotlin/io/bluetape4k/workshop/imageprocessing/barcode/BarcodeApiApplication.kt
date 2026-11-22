package io.bluetape4k.workshop.imageprocessing.barcode

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * `bluetape4k-dependencies` `2.1.0`으로 관리되는 bluetape4k-images barcode API를
 * 사용하는 Spring Boot 워크숍 애플리케이션입니다.
 */
@SpringBootApplication
class BarcodeApiApplication

/**
 * main은 이미지 업로드·검수·파생물 처리의 애플리케이션 경계에서 인증·tenant scope·입력 형식을 확인하고 성공 결과와 업무 오류를 계약된 응답으로 매핑합니다.
 *
 * 호출자는 저장소 확정 상태와 이벤트 결과를 확인한 뒤 다음 업무 단계를 진행해야 합니다.
 *
 * @param args 업무 처리에 필요한 args 값이며 관련 불변조건과 저장·이벤트 계약을 함께 만족해야 합니다.
 */
fun main(args: Array<String>) {
    runApplication<BarcodeApiApplication>(*args)
}
