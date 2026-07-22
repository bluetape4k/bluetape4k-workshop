package io.bluetape4k.workshop.commerce.voucherpool

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PreGeneratedVoucherPoolApplication

fun main(args: Array<String>) {
    @Suppress("SpreadOperator") // Spring Boot's entry point accepts Java varargs.
    runApplication<PreGeneratedVoucherPoolApplication>(*args)
}
