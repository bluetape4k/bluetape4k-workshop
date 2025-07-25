package io.bluetape4k.workshop.exposed

import io.bluetape4k.logging.KLogging
import io.bluetape4k.testcontainers.database.CockroachServer
import io.bluetape4k.testcontainers.database.MariaDBServer
import io.bluetape4k.testcontainers.database.MySQL5Server
import io.bluetape4k.testcontainers.database.MySQL8Server
import io.bluetape4k.testcontainers.database.PostgreSQLServer

object ContainerProvider: KLogging() {

    val mariadb by lazy { MariaDBServer.Launcher.mariadb }
    val mysql5 by lazy { MySQL5Server.Launcher.mysql }
    val mysql8 by lazy { MySQL8Server.Launcher.mysql }
    val postgres by lazy { PostgreSQLServer.Launcher.postgres }
    val cockroach by lazy { CockroachServer.Launcher.cockroach }
}
