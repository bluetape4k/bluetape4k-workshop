package io.bluetape4k.workshop.exposed.spring.boot.autoconfigure

import io.bluetape4k.concurrent.virtualthread.virtualFuture
import io.bluetape4k.workshop.exposed.spring.boot.tables.TestTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

// See https://docs.spring.io/spring/docs/current/spring-framework-reference/integration.html#scheduling-annotation-support
@Service
@Async // if not put then allTestData() has no error. In all cases allTestDataAsync will have error
// Issue comes from TransactionAspectSupport, implementation changed between spring version 5.1.X and 5.2.0
class AsyncExposedService {

    // if not using @EnableAsync, this method passes the test which should not
    // fun allTestData() = TestTable.selectAll().toList()

    // you need to put open otherwise @Transactional is not applied since spring plugin not applied (similar to maven kotlin plugin)
    fun allTestDataAsync(): CompletableFuture<List<ResultRow>> =
        virtualFuture {
            transaction {
                TestTable.selectAll().toList()
            }
        }.toCompletableFuture()
//        Executors.newVirtualThreadPerTaskExecutor()
//            .submit<List<ResultRow>> {
//                transaction {
//                    TestTable.selectAll().toList()
//                }
//            }
}
