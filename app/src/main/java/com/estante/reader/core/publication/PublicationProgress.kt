package com.estante.reader.core.publication

/** Map chapter-local progress to a content-weighted position for the shared reader slider. */
fun publicationGlobalProgress(chapters: List<PublicationChapter>, chapterIndex: Int, progress: Float): Float {
    if (chapters.isEmpty()) return 0f
    val weights = chapters.map { it.estimatedLength.coerceAtLeast(1).toLong() }
    val total = weights.sum().coerceAtLeast(1L).toDouble()
    val index = chapterIndex.coerceIn(0, chapters.lastIndex)
    val before = weights.take(index).sum().toDouble()
    return ((before + weights[index] * progress.coerceIn(0f, 1f)) / total).toFloat().coerceIn(0f, 1f)
}

/** Convert the shared slider value to the chapter and local progress stored by the reader. */
fun publicationLocationAtProgress(chapters: List<PublicationChapter>, progress: Float): Pair<Int, Float> {
    if (chapters.isEmpty()) return 0 to 0f
    val weights = chapters.map { it.estimatedLength.coerceAtLeast(1).toLong() }
    val total = weights.sum().coerceAtLeast(1L).toDouble()
    val target = progress.coerceIn(0f, 1f).toDouble() * total
    var consumed = 0.0
    weights.forEachIndexed { index, weight ->
        val end = consumed + weight
        if (target < end || index == weights.lastIndex) {
            return index to ((target - consumed) / weight).toFloat().coerceIn(0f, 1f)
        }
        consumed = end
    }
    return chapters.lastIndex to 1f
}
