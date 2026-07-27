package com.example.heart_rate_monitor_mobile.domain

/**
 * 版本号比较（纯 Kotlin，可单测）。
 * 支持 "v2.1"、"2.1.3"、"2.2-beta1" 等形式：按数字段逐段比较，缺省段视为 0，
 * 后缀（-beta 等）只在数字段完全相同时参与比较——有后缀者视为更低（预发布 < 正式）。
 */
object VersionComparator {

    /** @return 正数表示 [a] 更新，负数表示 [b] 更新，0 表示相同 */
    fun compare(a: String, b: String): Int {
        val (numsA, suffixA) = split(a)
        val (numsB, suffixB) = split(b)
        val size = maxOf(numsA.size, numsB.size)
        for (i in 0 until size) {
            val diff = numsA.getOrElse(i) { 0 } - numsB.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return when {
            suffixA.isEmpty() && suffixB.isEmpty() -> 0
            suffixA.isEmpty() -> 1   // 正式版 > 预发布
            suffixB.isEmpty() -> -1
            else -> suffixA.compareTo(suffixB)
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun split(raw: String): Pair<List<Int>, String> {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        val numberPart = trimmed.takeWhile { it.isDigit() || it == '.' }
        val suffix = trimmed.removePrefix(numberPart)
        val nums = numberPart.split('.').mapNotNull { it.toIntOrNull() }
        return nums to suffix
    }
}
