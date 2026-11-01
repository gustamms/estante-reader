package com.estante.reader.core.reader

/** How the reader chooses between one page and a facing-page spread. */
enum class PageDisplayMode { SINGLE_PAGE, DOUBLE_PAGE, AUTOMATIC }

/** Stable page grouping. [first] is the earlier archive page, regardless of visual direction. */
data class ReaderSpread(val first: Int, val second: Int?) {
    val last: Int get() = second ?: first
    fun contains(page: Int): Boolean = page == first || page == second
}

object ReaderSpreadBuilder {
    fun forPage(pageIndex: Int, pageCount: Int, separateCover: Boolean): ReaderSpread? {
        if (pageCount <= 0 || pageIndex !in 0 until pageCount) return null
        if (separateCover && pageIndex == 0) return ReaderSpread(0, null)
        val groupStart = if (separateCover) 1 else 0
        val first = groupStart + ((pageIndex - groupStart) / 2) * 2
        val second = (first + 1).takeIf { it < pageCount }
        return ReaderSpread(first, second)
    }

    fun next(spread: ReaderSpread, pageCount: Int, separateCover: Boolean): Int? =
        if (spread.last + 1 < pageCount) forPage(spread.last + 1, pageCount, separateCover)?.first else null

    fun previous(spread: ReaderSpread, pageCount: Int, separateCover: Boolean): Int? =
        if (spread.first > 0) forPage(spread.first - 1, pageCount, separateCover)?.first else null
}
