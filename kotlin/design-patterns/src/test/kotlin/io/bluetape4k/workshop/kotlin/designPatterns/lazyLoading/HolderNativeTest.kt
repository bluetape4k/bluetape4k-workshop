package io.bluetape4k.workshop.kotlin.designPatterns.lazyLoading

internal class HolderNativeTest: AbstractHolderTest() {

    private val holder = HolderNative()

    override fun getInternalHeavyValue(): Heavy? {
        val field = holder.javaClass.getDeclaredField("heavy")
        field.isAccessible = true

        return field.get(holder) as? Heavy
    }

    override fun getHeavy(): Heavy? {
        return holder.getHeavy()
    }
}
