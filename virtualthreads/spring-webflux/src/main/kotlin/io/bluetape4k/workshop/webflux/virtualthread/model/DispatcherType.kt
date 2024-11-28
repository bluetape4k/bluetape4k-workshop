package io.bluetape4k.workshop.webflux.virtualthread.model

enum class DispatcherType(val code: String) {
    Default("default"),
    IO("io"),
    Custom("custom"),
    VirtualThread("virtual-thread")
}
