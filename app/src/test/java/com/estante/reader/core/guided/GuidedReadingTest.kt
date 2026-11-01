package com.estante.reader.core.guided

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidedReadingTest {
    private val resolver = GeometricReadingOrderResolver()

    @Test
    fun `orders rows top to bottom and within rows left to right`() {
        val regions = listOf(
            region("bottom-right", 0.6f, 0.7f, 0.9f, 0.9f),
            region("top-right", 0.6f, 0.1f, 0.9f, 0.3f),
            region("bottom-left", 0.1f, 0.7f, 0.4f, 0.9f),
            region("top-left", 0.1f, 0.1f, 0.4f, 0.3f),
        )
        assertEquals(
            listOf("top-left", "top-right", "bottom-left", "bottom-right"),
            resolver.resolve(regions, ReadingDirection.LEFT_TO_RIGHT).map { it.id },
        )
    }

    @Test
    fun `orders within rows right to left for manga`() {
        val regions = listOf(region("left", 0.1f, 0.1f, 0.4f, 0.3f), region("right", 0.6f, 0.1f, 0.9f, 0.3f))
        assertEquals(listOf("right", "left"), resolver.resolve(regions, ReadingDirection.RIGHT_TO_LEFT).map { it.id })
    }

    @Test
    fun `stable ordering uses id for identical geometry`() {
        val regions = listOf(region("z", 0.2f, 0.2f, 0.4f, 0.4f), region("a", 0.2f, 0.2f, 0.4f, 0.4f))
        assertEquals(listOf("a", "z"), resolver.resolve(regions, ReadingDirection.LEFT_TO_RIGHT).map { it.id })
    }

    @Test
    fun `normalized rectangles reject non-finite inverted and out-of-bounds coordinates`() {
        assertThrows(IllegalArgumentException::class.java) { NormalizedRect(0.4f, 0f, 0.2f, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedRect(0f, 0f, Float.NaN, 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { NormalizedRect(-0.1f, 0f, 0.2f, 0.5f) }
        assertNull(NormalizedRect.clipped(1.2f, 0f, 2f, 0.5f))
        assertNull(NormalizedRect.clipped(0.8f, 0f, 0.2f, 0.5f))
        assertEquals(NormalizedRect(0f, 0f, 1f, 0.8f), NormalizedRect.clipped(-2f, -1f, 4f, 0.8f))
    }

    @Test
    fun `expanded bounds remain normalized and overlap score is bounded`() {
        val original = NormalizedRect(0.01f, 0.01f, 0.99f, 0.99f)
        val expanded = original.expanded(0.25f)
        assertTrue(expanded.left >= 0f && expanded.top >= 0f)
        assertTrue(expanded.right <= 1f && expanded.bottom <= 1f)
        assertTrue(expanded.width > 0f && expanded.height > 0f)
        assertTrue(original.intersectionOverUnion(expanded) in 0f..1f)
    }

    @Test
    fun `manual overrides resize and exclude without mutating detector results`() {
        val first = region("one", 0.1f, 0.1f, 0.3f, 0.3f)
        val second = region("two", 0.6f, 0.1f, 0.8f, 0.3f)
        val editedBounds = NormalizedRect(0.05f, 0.05f, 0.35f, 0.35f)
        val page = GuidedPage(
            detected = listOf(first, second),
            overrides = listOf(
                RegionOverride("one", bounds = editedBounds, order = 1),
                RegionOverride("two", excluded = true),
            ),
        )
        assertEquals(listOf("one"), page.effectiveRegions().map { it.id })
        assertEquals(editedBounds, page.effectiveRegions().single().bounds)
        assertEquals(first.bounds, page.detected.first().bounds)
    }

    @Test
    fun `manual order takes precedence over geometric order`() {
        val page = GuidedPage(
            detected = listOf(region("left", 0.1f, 0.1f, 0.3f, 0.3f), region("right", 0.7f, 0.1f, 0.9f, 0.3f)),
            overrides = listOf(RegionOverride("left", order = 1), RegionOverride("right", order = 0)),
        )
        assertEquals(
            listOf("right", "left"),
            page.orderedRegions(ReadingDirection.LEFT_TO_RIGHT, resolver).map { it.id },
        )
    }

    @Test
    fun `detector confidence must be finite and within unit interval`() {
        assertThrows(IllegalArgumentException::class.java) { region("bad", 0f, 0f, 1f, 1f, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { region("bad", 0f, 0f, 1f, 1f, 1.1f) }
        assertNull(region("manual", 0f, 0f, 1f, 1f).confidence)
    }

    private fun region(id: String, l: Float, t: Float, r: Float, b: Float, confidence: Float? = null) =
        GuidedRegion(id, NormalizedRect(l, t, r, b), confidence = confidence)
}
