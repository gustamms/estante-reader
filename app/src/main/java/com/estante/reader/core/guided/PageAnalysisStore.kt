package com.estante.reader.core.guided

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Stores only derived regions. A missing value means the page still needs analysis. */
class PageAnalysisStore(context: Context) {
    private val root = File(context.filesDir, "page-analysis")

    fun read(documentFingerprint: String, modelVersion: String, pageIndex: Int): List<GuidedRegion>? {
        val file = pageFile(documentFingerprint, modelVersion, pageIndex)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            require(json.getInt("schema") == SCHEMA_VERSION)
            val regions = json.getJSONArray("regions")
            buildList {
                for (index in 0 until regions.length()) {
                    val region = regions.getJSONObject(index)
                    val bounds = region.getJSONObject("bounds")
                    add(
                        GuidedRegion(
                            id = region.getString("id"),
                            bounds = NormalizedRect(
                                left = bounds.getDouble("left").toFloat(),
                                top = bounds.getDouble("top").toFloat(),
                                right = bounds.getDouble("right").toFloat(),
                                bottom = bounds.getDouble("bottom").toFloat(),
                            ),
                            kind = RegionKind.valueOf(region.getString("kind")),
                            confidence = if (region.isNull("confidence")) null else region.getDouble("confidence").toFloat(),
                        ),
                    )
                }
            }
        }.getOrNull()
    }

    fun write(
        documentFingerprint: String,
        modelVersion: String,
        pageIndex: Int,
        regions: List<GuidedRegion>,
    ) {
        val file = pageFile(documentFingerprint, modelVersion, pageIndex)
        file.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Could not create analysis directory" }
        }
        val rows = JSONArray()
        regions.forEach { region ->
            rows.put(
                JSONObject()
                    .put("id", region.id)
                    .put("kind", region.kind.name)
                    .put("confidence", region.confidence ?: JSONObject.NULL)
                    .put(
                        "bounds",
                        JSONObject()
                            .put("left", region.bounds.left.toDouble())
                            .put("top", region.bounds.top.toDouble())
                            .put("right", region.bounds.right.toDouble())
                            .put("bottom", region.bounds.bottom.toDouble()),
                    ),
            )
        }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(JSONObject().put("schema", SCHEMA_VERSION).put("regions", rows).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun pageFile(documentFingerprint: String, modelVersion: String, pageIndex: Int): File {
        require(pageIndex >= 0)
        val documentKey = safeKey(documentFingerprint)
        val modelKey = safeKey(modelVersion)
        return File(File(File(root, documentKey), modelKey), "$pageIndex.json")
    }

    private fun safeKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
