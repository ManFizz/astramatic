package com.manfis.astramatic

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform