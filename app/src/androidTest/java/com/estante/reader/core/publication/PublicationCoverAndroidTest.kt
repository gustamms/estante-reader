package com.estante.reader.core.publication

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicationCoverAndroidTest {
    @Test fun coverUsesContainedCanonicalPathAndSurvivesManifestReload() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"cover-${System.nanoTime()}").apply{mkdirs()}
        try {
            File(root,"META-INF").mkdirs();File(root,"OEBPS").mkdirs()
            File(root,"META-INF/container.xml").writeText("<container><rootfiles><rootfile full-path='OEBPS/book.opf'/></rootfiles></container>")
            File(root,"OEBPS/book.opf").writeText("<package><manifest><item id='cover' href='cover.jpg' properties='cover-image'/></manifest></package>")
            val cover=File(root,"OEBPS/cover.jpg").apply{writeBytes(byteArrayOf(1,2,3))}
            assertEquals(cover.canonicalFile,PublicationCoverResolver.find(root))
            val chapter=File(root,"OEBPS/chapter.html").apply{writeText("<p>Book</p>")}
            PublicationManifestStore.save(ImportedPublication("EPUB","Book",null,listOf(PublicationChapter("OEBPS/chapter.html","Chapter")),root,cover.canonicalFile))
            assertEquals("OEBPS/cover.jpg",JSONObject(File(root,"estante-publication.json").readText()).getString("cover"))
            assertEquals(cover.canonicalFile,PublicationManifestStore.load(root)?.coverFile)
            File(root,"OEBPS/book.opf").writeText("<package><manifest><item href='../../outside.jpg' properties='cover-image'/></manifest></package>")
            assertNull(PublicationCoverResolver.find(root))
        } finally { root.deleteRecursively() }
    }
}
