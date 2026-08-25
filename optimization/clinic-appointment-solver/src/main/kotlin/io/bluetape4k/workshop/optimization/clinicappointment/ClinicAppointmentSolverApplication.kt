package io.bluetape4k.workshop.optimization.clinicappointment

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Synthetic clinic appointment Solver reference application. */
@SpringBootApplication
class ClinicAppointmentSolverApplication

fun main(args: Array<String>) {
    runApplication<ClinicAppointmentSolverApplication>(*args)
}
