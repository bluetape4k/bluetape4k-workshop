package io.bluetape4k.workshop.mapstruct

import org.mapstruct.factory.Mappers

/**
 * `<T>` 수형의 Mapstruct 용 `Mapper` 를 생성하는 함수
 */
inline fun <reified T> mapper(): T = Mappers.getMapper(T::class.java)
