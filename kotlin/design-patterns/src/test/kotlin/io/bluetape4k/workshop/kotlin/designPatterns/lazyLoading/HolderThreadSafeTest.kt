package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading

internal class HolderThreadSafeTest: AbstractHolderTest() {

    private val holder = HolderThreadSafe()

    override fun getInternalHeavyValue(): Heavy? {
        val field = holder.javaClass.getDeclaredField("heavy")
        field.isAccessible = true

        return field.get(holder) as? Heavy
    }

    override fun getHeavy(): Heavy? {
        return holder.getHeavy()
    }
}
