package com.suseoaa.castpigeon

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform