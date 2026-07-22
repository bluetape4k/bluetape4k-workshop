package io.bluetape4k.workshop.commerce.metering.contract

object UsageBillingHttpContract {
    private const val TENANT = "tenant-a"
    private const val METER_PATH = "/api/v1/tenants/$TENANT/meters"

    fun verifyRegistrationAndRetry(client: ContractHttpClient) {
        val created = client.post(METER_PATH, TENANT, "meter-registration", meterBody("request"))
        check(created.status == 201) { "meter registration status=${created.status}, body=${created.body}" }
        check(created.body.contains("api_calls")) { "meter response does not identify api_calls: ${created.body}" }

        val replay = client.post(METER_PATH, TENANT, "meter-registration", meterBody("request"))
        check(replay.status == 201) { "idempotent replay status=${replay.status}, body=${replay.body}" }
        check(replay.firstHeader("Idempotency-Replayed") == "true") {
            "idempotent replay header missing: ${replay.headers}"
        }

        val conflict = client.post(METER_PATH, TENANT, "meter-registration", meterBody("call"))
        check(conflict.status == 409) { "idempotency conflict status=${conflict.status}, body=${conflict.body}" }
        check(conflict.body.contains("idempotency_conflict")) {
            "idempotency conflict code missing: ${conflict.body}"
        }

        val crossTenant = client.post("/api/v1/tenants/tenant-b/meters", TENANT, "cross-tenant", meterBody("request"))
        check(crossTenant.status == 400) { "cross-tenant status=${crossTenant.status}, body=${crossTenant.body}" }
        check(crossTenant.body.contains("tenant_mismatch")) { "tenant mismatch code missing: ${crossTenant.body}" }

        val missingKey = client.post(METER_PATH, TENANT, null, meterBody("request"))
        check(missingKey.status == 403) { "missing idempotency key status=${missingKey.status}, body=${missingKey.body}" }
    }

    private fun meterBody(unit: String): String = """{"code":"api_calls","unit":"$unit"}"""
}
