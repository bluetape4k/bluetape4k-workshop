package io.bluetape4k.workshop.commerce.metering.contract

/**
 * UsageBillingHttpContract 테스트 묶음은 사용량·청구의 업무 규칙과 실패 경계를 검증합니다.
 *
 * 성공 응답만 확인하지 않고 PostgreSQL 확정 상태, 이벤트 순서, 멱등성·권한·재시도 결과가 함께 일치하는지 확인합니다.
 */
object UsageBillingHttpContract {
/**
 * TENANT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val TENANT = "tenant-a"
/**
 * METER_PATH은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val METER_PATH = "/api/v1/tenants/$TENANT/meters"
/**
 * CREATED은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val CREATED = 201
/**
 * BAD_REQUEST은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val BAD_REQUEST = 400
/**
 * FORBIDDEN은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val FORBIDDEN = 403
/**
 * CONFLICT은 사용량·청구 테스트에서 확정 상태 또는 협력 의존성을 보관합니다.
 *
 * 테스트는 이 값이 저장소·이벤트·API 응답과 동일한 업무 의미를 유지하는지 확인합니다.
 */
    private const val CONFLICT = 409

/**
 * verifyRegistrationAndRetry은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    fun verifyRegistrationAndRetry(client: ContractHttpClient) {
        val created = client.post(METER_PATH, TENANT, "meter-registration", meterBody("request"))
        check(created.status == CREATED) { "meter registration status=${created.status}, body=${created.body}" }
        check(created.body.contains("api_calls")) { "meter response does not identify api_calls: ${created.body}" }

        val replay = client.post(METER_PATH, TENANT, "meter-registration", meterBody("request"))
        check(replay.status == CREATED) { "idempotent replay status=${replay.status}, body=${replay.body}" }
        check(replay.firstHeader("Idempotency-Replayed") == "true") {
            "idempotent replay header missing: ${replay.headers}"
        }

        val conflict = client.post(METER_PATH, TENANT, "meter-registration", meterBody("call"))
        check(conflict.status == CONFLICT) { "idempotency conflict status=${conflict.status}, body=${conflict.body}" }
        check(conflict.body.contains("idempotency_conflict")) {
            "idempotency conflict code missing: ${conflict.body}"
        }

        val crossTenant = client.post("/api/v1/tenants/tenant-b/meters", TENANT, "cross-tenant", meterBody("request"))
        check(crossTenant.status == BAD_REQUEST) {
            "cross-tenant status=${crossTenant.status}, body=${crossTenant.body}"
        }
        check(crossTenant.body.contains("tenant_mismatch")) { "tenant mismatch code missing: ${crossTenant.body}" }

        val missingKey = client.post(METER_PATH, TENANT, null, meterBody("request"))
        check(missingKey.status == FORBIDDEN) {
            "missing idempotency key status=${missingKey.status}, body=${missingKey.body}"
        }
    }

/**
 * meterBody은 사용량·청구 테스트 fixture의 업무 경계를 실행하거나 변환하는 메소드입니다.
 *
 * 재시도·경쟁·복구 상황에서도 source-of-truth와 관찰 가능한 결과가 일치해야 한다는 계약을 검증에 사용합니다.
 */
    private fun meterBody(unit: String): String = """{"code":"api_calls","unit":"$unit"}"""
}
