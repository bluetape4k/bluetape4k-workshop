package io.bluetape4k.workshop.jsonview.dto

interface Views {

    interface Public
    interface Analytics
    interface Internal: Public, Analytics

}
