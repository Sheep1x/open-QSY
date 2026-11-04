package com.openqsy.glasses.input

fun cycleIndex(current: Int, count: Int, delta: Int): Int {
    if (count <= 0) return 0
    val r = (current + delta) % count
    return if (r < 0) r + count else r
}
