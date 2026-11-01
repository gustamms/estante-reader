package com.estante.reader.core.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.estante.reader.core.guided.GuidedRegion
import com.estante.reader.core.guided.NormalizedRect
import com.estante.reader.core.guided.RegionKind
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the pinned comic bubble detector locally with ONNX Runtime's CPU execution provider. */
class OrtBubbleDetector(
    context: Context,
    private val scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
) : Closeable {
    val modelVersion: String = VERSION

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val environmentDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { OrtEnvironment.getEnvironment() }
    private val sessionDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loadSession() }
    private var sessionOptions: OrtSession.SessionOptions? = null

    init {
        require(scoreThreshold.isFinite() && scoreThreshold in 0f..1f)
    }

    private fun loadSession(): OrtSession {
        val modelBytes = appContext.assets.open(MODEL_ASSET).use { it.readBytes() }
        check(sha256(modelBytes) == MODEL_SHA256) {
            "The bundled comic detector does not match its pinned checksum."
        }
        val options = OrtSession.SessionOptions()
        var createdSession: OrtSession? = null
        try {
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            val session = environmentDelegate.value.createSession(modelBytes, options)
            createdSession = session
            require(session.inputNames == INPUT_NAMES) {
                "Unexpected model inputs: ${session.inputNames}"
            }
            require(session.outputNames.containsAll(OUTPUT_NAMES)) {
                "Unexpected model outputs: ${session.outputNames}"
            }
            sessionOptions = options
            return session
        } catch (error: Exception) {
            createdSession?.close()
            options.close()
            throw error
        }
    }

    /**
     * Returns only class 0 (`bubble`) as normalized page coordinates. Text and free-text boxes
     * are deliberately excluded: guided reading needs the balloon bounds, not an OCR text box.
     * The caller must execute this blocking method away from the main thread.
     */
    @Synchronized
    fun detect(bitmap: Bitmap): List<GuidedRegion> {
        check(!closed.get()) { "Bubble detector is closed." }
        val activeSession = sessionDelegate.value
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "A valid page bitmap is required."
        }

        val inputPixels = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val resized = if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        }
        try {
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            val planeSize = INPUT_SIZE * INPUT_SIZE
            for (index in pixels.indices) {
                val pixel = pixels[index]
                inputPixels[index] = Color.red(pixel) / 255f
                inputPixels[planeSize + index] = Color.green(pixel) / 255f
                inputPixels[planeSize * 2 + index] = Color.blue(pixel) / 255f
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }

        val imageTensor = OnnxTensor.createTensor(
            environmentDelegate.value,
            FloatBuffer.wrap(inputPixels),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
        )
        val targetSizeTensor = OnnxTensor.createTensor(
            environmentDelegate.value,
            LongBuffer.wrap(longArrayOf(bitmap.width.toLong(), bitmap.height.toLong())),
            longArrayOf(1, 2),
        )
        imageTensor.use { image ->
            targetSizeTensor.use { targetSizes ->
                activeSession.run(mapOf("images" to image, "orig_target_sizes" to targetSizes)).use { result ->
                    val labels = result.get("labels").orElseThrow {
                        IllegalStateException("Detector output is missing labels.")
                    } as OnnxTensor
                    val boxes = result.get("boxes").orElseThrow {
                        IllegalStateException("Detector output is missing boxes.")
                    } as OnnxTensor
                    val scores = result.get("scores").orElseThrow {
                        IllegalStateException("Detector output is missing scores.")
                    } as OnnxTensor
                    return decodeDetections(labels, boxes, scores, bitmap.width, bitmap.height)
                }
            }
        }
    }

    private fun decodeDetections(
        labelsTensor: OnnxTensor,
        boxesTensor: OnnxTensor,
        scoresTensor: OnnxTensor,
        pageWidth: Int,
        pageHeight: Int,
    ): List<GuidedRegion> {
        val labels = labelsTensor.longBuffer ?: error("Detector labels are not int64.")
        val boxes = boxesTensor.floatBuffer ?: error("Detector boxes are not float32.")
        val scores = scoresTensor.floatBuffer ?: error("Detector scores are not float32.")
        val count = minOf(labels.remaining(), scores.remaining(), boxes.remaining() / 4)
        val accepted = mutableListOf<Pair<Int, GuidedRegion>>()

        repeat(count) { index ->
            val label = labels.get()
            val left = boxes.get()
            val top = boxes.get()
            val right = boxes.get()
            val bottom = boxes.get()
            val score = scores.get()
            if (label != BUBBLE_CLASS_ID || score < scoreThreshold || !score.isFinite()) return@repeat

            val bounds = NormalizedRect.clipped(
                left = left / pageWidth,
                top = top / pageHeight,
                right = right / pageWidth,
                bottom = bottom / pageHeight,
            ) ?: return@repeat
            accepted += index to GuidedRegion(
                id = "${MODEL_ID}_$index",
                bounds = bounds,
                kind = RegionKind.SPEECH_BALLOON,
                confidence = score,
            )
        }

        return accepted
            .sortedWith(compareBy<Pair<Int, GuidedRegion>> { it.second.bounds.centerY }
                .thenBy { it.second.bounds.centerX }
                .thenBy { it.first })
            .map { it.second }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true) && sessionDelegate.isInitialized()) {
            sessionDelegate.value.close()
            sessionOptions?.close()
            sessionOptions = null
        }
        // OrtEnvironment is a JVM singleton. ORT owns its shutdown lifecycle.
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val VERSION = "ogkalu-rtdetr-v4s-int8@16e8a622f91fabc6b5b65c96d32d1183f8843546"

        private const val MODEL_ID = "ogkalu-rtdetr-v4s"
        const val INPUT_SIZE = 640
        const val BUBBLE_CLASS_ID = 0L
        const val DEFAULT_SCORE_THRESHOLD = 0.30f
        const val MODEL_ASSET = "models/comic-bubble-detector-v4s-int8.onnx"
        const val MODEL_SHA256 = "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79"
        val INPUT_NAMES = setOf("images", "orig_target_sizes")
        val OUTPUT_NAMES = setOf("labels", "boxes", "scores")
    }
}
