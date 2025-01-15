package io.bluetape4k.workshop.exposed.spring.transaction

import io.bluetape4k.support.uninitialized
import org.jetbrains.exposed.spring.SpringTransactionManager
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class])
abstract class SpringTransactionTestBase {

    @Autowired
    protected val ctx: ApplicationContext = uninitialized()

    @Autowired
    protected val transactionManager: PlatformTransactionManager = uninitialized()
}

inline fun PlatformTransactionManager.execute(
    propagationBehavior: Int = TransactionDefinition.PROPAGATION_REQUIRED,
    isolationLevel: Int = TransactionDefinition.ISOLATION_DEFAULT,
    readOnly: Boolean = false,
    timeout: Int? = null,
    crossinline block: (TransactionStatus) -> Unit,
) {
    if (this !is SpringTransactionManager) {
        error("Wrong transaction manager. ${this.javaClass.name}, use Exposed's SpringTransactionManager")
    }

    val tt = TransactionTemplate(this).also {
        it.propagationBehavior = propagationBehavior
        it.isolationLevel = isolationLevel
        if (readOnly) it.isReadOnly = true
        timeout?.run { it.timeout = timeout }
    }

    tt.executeWithoutResult {
        block(it)
    }
}
