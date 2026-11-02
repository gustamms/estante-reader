package com.estante.reader.core.archive

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Renders PDF pages on demand so the reader can reuse its existing bitmap-based pipeline. */
class PdfPageDocument private constructor(
    private val renderer: PdfRenderer,
) : PageDocument {
    private val rendererLock = Any()
    private val documentPageCount = renderer.pageCount
    private var closed = false

    override val pageCount: Int get() = documentPageCount

    override fun readPage(index: Int): ByteArray =
        readPage(index, MAX_PAGE_DIMENSION.toInt(), MAX_PAGE_PIXELS.toLong())

    override fun readPage(index: Int, maxDimension: Int, maxPixels: Long): ByteArray = synchronized(rendererLock) {
        check(!closed) { "O PDF já foi fechado." }
        require(index in 0 until documentPageCount) { "Página de PDF inválida: $index." }
        require(maxDimension > 0 && maxPixels > 0) { "Os limites de renderização precisam ser positivos." }

        val page = renderer.openPage(index)
        try {
            val dimensions = rasterDimensions(page.width, page.height, maxDimension, maxPixels)
            val bitmap = Bitmap.createBitmap(dimensions.first, dimensions.second, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        "Não foi possível preparar a página do PDF."
                    }
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }

    override fun close() = synchronized(rendererLock) {
        if (!closed) {
            closed = true
            renderer.close()
        }
    }

    private fun rasterDimensions(
        pageWidth: Int,
        pageHeight: Int,
        maxDimension: Int,
        maxPixels: Long,
    ): Pair<Int, Int> {
        require(pageWidth > 0 && pageHeight > 0) { "O PDF contém uma página com dimensões inválidas." }
        val width = pageWidth.toDouble()
        val height = pageHeight.toDouble()
        val scale = min(
            maxDimension / maxOf(width, height),
            sqrt(maxPixels.toDouble() / (width * height)),
        )
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    companion object {
        private const val MAX_PAGE_DIMENSION = 2_800.0
        private const val MAX_PAGE_PIXELS = 7_840_000.0
        private const val MAX_PAGE_COUNT = 4_000

        fun openFile(file: File): PdfPageDocument {
            if (!file.isFile || !file.canRead()) {
                throw InvalidComicArchiveException("O PDF não está disponível para leitura.")
            }
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (error: Exception) {
                throw InvalidComicArchiveException("Não foi possível abrir o PDF.", error)
            }
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (error: SecurityException) {
                runCatching { descriptor.close() }
                throw UnsupportedPageDocumentException("PDF protegido por senha não é suportado ainda.", error)
            } catch (error: Exception) {
                runCatching { descriptor.close() }
                throw InvalidComicArchiveException("O arquivo PDF está inválido ou não é compatível.", error)
            }

            if (renderer.pageCount !in 1..MAX_PAGE_COUNT) {
                renderer.close()
                throw ComicArchiveLimitException("O PDF precisa ter entre 1 e $MAX_PAGE_COUNT páginas.")
            }
            return PdfPageDocument(renderer)
        }
    }
}

class UnsupportedPageDocumentException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
