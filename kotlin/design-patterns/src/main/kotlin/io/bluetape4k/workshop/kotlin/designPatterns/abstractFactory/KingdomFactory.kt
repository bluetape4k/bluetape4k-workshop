package io.bluetape4k.workshop.kotlin.designPatterns.abstractFactory

interface KingdomFactory {

    fun createCastle(): Castle

    fun createKing(): King

    fun createArmy(): Army
}
