package io.bluetape4k.workshop.kotlin.patterns

import org.amshove.kluent.shouldBe
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Singleton {

    @Test
    fun `kotlin에서 singleton은 object 키워드를 사용한다`() {
        // val myFavoriteMovies = listOf("The Matrix", "Inception", "Interstellar")

        val myMoviews = NoMoviesList
        val yourMoview = NoMoviesList

        myMoviews shouldBe yourMoview       // 같은 인스턴스이다.

        assertTrue { emptyList<String>() === listOf<String>() } // 같은 인스턴스이다.

        // data object 는 toString() 시에 클래스명만 출력한다.
        println("object vs data object: $NoMoviesList vs $NoMoviesListDataObject")

        Logger.log("Hello")
        Logger.log("World!")
    }

    data object NoMoviesListDataObject

    // Kotlin의 delegate pattern 을 사용하면 더 간결하게 구현할 수 있다. (여기서는 class delegate)
    @Suppress("JavaDefaultMethodsNotOverriddenByDelegation")
    object NoMoviesList: List<String> by emptyList() {
        private val empty = emptyList<String>()

//        override val size: Int get() = empty.size
//        override fun get(index: Int): String = empty[index]
//        override fun isEmpty() = empty.isEmpty()
//        override fun iterator() = empty.iterator()
//        override fun listIterator() = empty.listIterator()
//        override fun listIterator(index: Int) = empty.listIterator(index)
//        override fun subList(fromIndex: Int, toIndex: Int) = empty.subList(fromIndex, toIndex)
//        override fun lastIndexOf(element: String) = empty.lastIndexOf(element)
//        override fun indexOf(element: String) = empty.indexOf(element)
//        override fun containsAll(elements: Collection<String>) = empty.containsAll(elements)
//        override fun contains(element: String) = empty.contains(element)
    }

    object Logger {
        init {
            println("Initialize Logger for the first time")
        }

        fun log(message: String) {
            println("Logging $message")
        }
    }
}
