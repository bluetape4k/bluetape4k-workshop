package io.bluetape4k.workshop.cassandra.multitenancy.row

import io.bluetape4k.logging.KLogging

object TenantIdProvider: KLogging() {

    val tenantId: ThreadLocal<String> = ThreadLocal.withInitial { "" }

}
