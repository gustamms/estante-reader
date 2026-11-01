package com.estante.reader.core.guided

import kotlin.math.max

/** A rectangle expressed as fractions of the source page, independent of its pixel size. */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "Rectangle coordinates must be finite"
        }
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f) {
            "Rectangle coordinates must be within the normalized page"
        }
        require(left < right && top < bottom) { "Rectangle must have positive area" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Add a fractional margin while keeping the result inside the page. */
    fun expanded(marginFraction: Float): NormalizedRect {
        require(marginFraction.isFinite() && marginFraction >= 0f)
        val xMargin = width * marginFraction
        val yMargin = height * marginFraction
        return NormalizedRect(
            left = (left - xMargin).coerceAtLeast(0f),
            top = (top - yMargin).coerceAtLeast(0f),
            right = (right + xMargin).coerceAtMost(1f),
            bottom = (bottom + yMargin).coerceAtMost(1f),
        )
    }

    /** Intersection over union, useful for comparing detector output with annotations. */
    fun intersectionOverUnion(other: NormalizedRect): Float {
        val intersectionWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val intersectionHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        val union = width * height + other.width * other.height - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    companion object {
        /** Converts a possibly out-of-page model result to a valid rectangle. */
        fun clipped(left: Float, top: Float, right: Float, bottom: Float): NormalizedRect? {
            if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return null
            val clippedLeft = left.coerceIn(0f, 1f)
            val clippedTop = top.coerceIn(0f, 1f)
            val clippedRight = right.coerceIn(0f, 1f)
            val clippedBottom = bottom.coerceIn(0f, 1f)
            if (clippedLeft >= clippedRight || clippedTop >= clippedBottom) return null
            return NormalizedRect(clippedLeft, clippedTop, clippedRight, clippedBottom)
        }
    }
}

enum class RegionKind { SPEECH_BALLOON, CAPTION, PANEL, TEXT, UNKNOWN }

/** A detected or user-created region. Confidence is absent for manual selections. */
data class GuidedRegion(
    val id: String,
    val bounds: NormalizedRect,
    val kind: RegionKind = RegionKind.SPEECH_BALLOON,
    val confidence: Float? = null,
) {
    init {
        require(id.isNotBlank()) { "Region id must not be blank" }
        require(confidence == null || confidence.isFinite() && confidence in 0f..1f) {
            "Confidence must be null or within 0..1"
        }
    }
}

enum class ReadingDirection { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

/** Boundary for an on-device detector. The core does not choose or bundle a model. */
fun interface RegionDetector<Page> {
    fun detect(page: Page): List<GuidedRegion>
}

/** A user correction is stored separately from detector output. */
data class RegionOverride(
    val regionId: String,
    val bounds: NormalizedRect? = null,
    val kind: RegionKind? = null,
    val order: Int? = null,
    val excluded: Boolean = false,
) {
    init {
        require(regionId.isNotBlank())
        require(order == null || order >= 0)
    }
}

/** A page-local set of detector results and durable user edits. */
data class GuidedPage(
    val detected: List<GuidedRegion>,
    val overrides: List<RegionOverride> = emptyList(),
) {
    init {
        require(detected.map { it.id }.distinct().size == detected.size) { "Detected region ids must be unique" }
        require(overrides.map { it.regionId }.distinct().size == overrides.size) { "Override ids must be unique" }
        require(overrides.mapNotNull { it.order }.distinct().size == overrides.count { it.order != null }) {
            "Explicit region order values must be unique"
        }
    }

    fun effectiveRegions(): List<GuidedRegion> {
        val byId = overrides.associateBy { it.regionId }
        return detected.mapNotNull { region ->
            val edit = byId[region.id] ?: return@mapNotNull region
            if (edit.excluded) return@mapNotNull null
            region.copy(bounds = edit.bounds ?: region.bounds, kind = edit.kind ?: region.kind)
        }
    }

    /** Resolve detected geometry first, then place explicitly ranked regions at their saved slots. */
    fun orderedRegions(
        direction: ReadingDirection,
        resolver: ReadingOrderResolver = GeometricReadingOrderResolver(),
    ): List<GuidedRegion> {
        val geometric = resolver.resolve(effectiveRegions(), direction)
        val ranked = overrides.mapNotNull { edit -> edit.order?.let { edit.regionId to it } }
            .filter { (id, _) -> geometric.any { it.id == id } }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first })
        if (ranked.isEmpty()) return geometric

        val result = MutableList<GuidedRegion?>(geometric.size) { null }
        val reserved = mutableSetOf<String>()
        ranked.forEachIndexed { position, (id, _) ->
            val region = geometric.first { it.id == id }
            result[position.coerceAtMost(result.lastIndex)] = region
            reserved += id
        }
        val remaining = geometric.filterNot { it.id in reserved }.iterator()
        return result.map { it ?: remaining.next() }
    }
}

fun interface ReadingOrderResolver {
    fun resolve(regions: List<GuidedRegion>, direction: ReadingDirection): List<GuidedRegion>
}

/**
 * Deterministic geometric baseline: group vertically adjacent regions into rows, then order each
 * row in the chosen direction. Complex layouts can replace this with a panel-aware model.
 */
class GeometricReadingOrderResolver(
    private val rowOverlapThreshold: Float = 0.35f,
    private val centerTolerance: Float = 0.30f,
) : ReadingOrderResolver {
    init {
        require(rowOverlapThreshold in 0f..1f)
        require(centerTolerance.isFinite() && centerTolerance >= 0f)
    }

    override fun resolve(regions: List<GuidedRegion>, direction: ReadingDirection): List<GuidedRegion> {
        if (regions.size < 2) return regions.toList()
        val rows = mutableListOf<Row>()
        for (region in regions.sortedWith(compareBy<GuidedRegion> { it.bounds.centerY }.thenBy { it.id })) {
            val matchingRow = rows
                .map { row -> row to row.verticalMatch(region, rowOverlapThreshold, centerTolerance) }
                .filter { it.second }
                .minByOrNull { (row, _) -> kotlin.math.abs(row.centerY - region.bounds.centerY) }
                ?.first
            if (matchingRow == null) rows += Row(region) else matchingRow.add(region)
        }

        val horizontal = when (direction) {
            ReadingDirection.LEFT_TO_RIGHT -> compareBy<GuidedRegion> { it.bounds.left }
                .thenBy { it.bounds.centerX }
                .thenBy { it.bounds.top }
            ReadingDirection.RIGHT_TO_LEFT -> compareByDescending<GuidedRegion> { it.bounds.right }
                .thenByDescending { it.bounds.centerX }
                .thenBy { it.bounds.top }
        }.thenBy { it.id }

        return rows.sortedWith(compareBy<Row> { it.top }.thenBy { it.centerY })
            .flatMap { row -> row.regions.sortedWith(horizontal) }
    }

    private class Row(first: GuidedRegion) {
        val regions = mutableListOf(first)
        val top: Float get() = regions.minOf { it.bounds.top }
        val bottom: Float get() = regions.maxOf { it.bounds.bottom }
        val centerY: Float get() = regions.map { it.bounds.centerY }.average().toFloat()
        private val height: Float get() = bottom - top

        fun add(region: GuidedRegion) { regions += region }

        fun verticalMatch(region: GuidedRegion, overlapThreshold: Float, centerTolerance: Float): Boolean {
            val overlap = (minOf(bottom, region.bounds.bottom) - maxOf(top, region.bounds.top)).coerceAtLeast(0f)
            val smallerHeight = minOf(height, region.bounds.height)
            val overlapRatio = if (smallerHeight <= 0f) 0f else overlap / smallerHeight
            val centerDistance = kotlin.math.abs(centerY - region.bounds.centerY)
            val allowedCenterDistance = max(height, region.bounds.height) * centerTolerance
            return overlapRatio >= overlapThreshold || centerDistance <= allowedCenterDistance
        }
    }
}
