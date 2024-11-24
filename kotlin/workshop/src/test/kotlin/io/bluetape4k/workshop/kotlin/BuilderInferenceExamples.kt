package io.bluetape4k.workshop.kotlin

import org.junit.jupiter.api.Test

/**
 * Kotlin에서 Builder를 사용할 때, Builder의 타입을 추론하는 방법을 알아보자.
 *
 * * 참고:[Using builders with builder type inference](https://intotherealworld.tistory.com/43)
 * * 참고: [How to replace the builder pattern in Kotlin](https://code.gematik.de/tech/2023/01/11/no-builder-in-kotlin.html)
 */
class BuilderInferenceExamples {

    private fun <V> buildList(builder: MutableList<V>.() -> Unit): List<V> {
        return mutableListOf<V>().apply(builder)
    }

    private class ItemHolder<T> {
        private val items = mutableListOf<T>()

        fun addItem(x: T) {
            items.add(x)
        }

        fun getLastItem(): T? = items.lastOrNull()
    }

    private fun <T> ItemHolder<T>.addAllItems(xs: List<T>) {
        xs.forEach { addItem(it) }
    }

    private fun <T> itemHolderBuilder(builder: ItemHolder<T>.() -> Unit): ItemHolder<T> {
        return ItemHolder<T>().apply(builder)
    }

    @Test
    fun `List Builder`() {
        val list = buildList {
            add(1)
            add(2)
            add(3)
        }
        println(list)
    }

    @Test
    fun `ItemHolder Builder`() {
        val list = buildList<Int> {
            add(1)
            add(2)
            add(3)
        }

        val itemHolder = itemHolderBuilder {
            addItem("Hello")
            addItem("World")
        }
        println(itemHolder.getLastItem())

        // Builder Inference 덕분에 Int 수형을 명시하지 않아도 된다.
        val itemHolder2 = itemHolderBuilder {
            addAllItems(list)
        }
        println(itemHolder2.getLastItem())
    }
}
