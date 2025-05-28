package io.bluetape4k.workshop.exposed

import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("UnusedReceiverParameter")
private val JdbcTransaction.failedOn: String
    get() = currentTestDB?.name ?: currentDialectTest.name

fun JdbcTransaction.assertTrue(actual: Boolean) = assertTrue(actual, "Failed on $failedOn")
fun JdbcTransaction.assertFalse(actual: Boolean) = assertFalse(!actual, "Failed on $failedOn")
fun <T> JdbcTransaction.assertEquals(exp: T, act: T) = assertEquals(exp, act, "Failed on $failedOn")
fun <T> JdbcTransaction.assertEquals(exp: T, act: Collection<T>) =
    assertEquals(exp, act.single(), "Failed on $failedOn")

fun JdbcTransaction.assertFailAndRollback(message: String, block: () -> Unit) {
    commit()
    assertFails("Failed on ${currentDialectTest.name}. $message") {
        block()
        commit()
    }
    rollback()
}

inline fun <reified T: Throwable> expectException(body: () -> Unit) {
    assertFailsWith<T>("Failed on ${currentDialectTest.name}") {
        body()
    }
}
