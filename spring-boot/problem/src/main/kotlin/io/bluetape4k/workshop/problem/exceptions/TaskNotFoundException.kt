package io.bluetape4k.workshop.problem.exceptions

class TaskNotFoundException(
    val taskId: Long,
    cause: Throwable? = null,
): ExampleException(format(taskId), cause) {

    companion object {
        internal fun format(taskId: Long): String = "Task[$taskId] is not found"
    }
}
