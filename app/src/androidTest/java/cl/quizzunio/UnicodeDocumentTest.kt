package cl.quizzunio

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.study.StudyDocumentReader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@SdkSuppress(minSdkVersion = 29)
class UnicodeDocumentTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = StudyDocumentReader(context)

    @Test fun textExtractionPreservesUnicodeAndEveryWhitespaceSequence() {
        val literal = "  Cálculo ñ, n\u0303, ∑, 中文, 😀, 𐐀\r\n\t\u00A0  \n\n\nTexto final\r\n "
        val utf8 = literal.toByteArray(Charsets.UTF_8)
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + literal.toByteArray(Charsets.UTF_16LE)
        for (bytes in listOf(utf8, byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + utf8, utf16)) {
            withDocument("txt", "text/plain", bytes) { assertEquals(literal, reader.read(it).text) }
        }
        val legacy = " Cañón y energía\t\r\n"
        withDocument("txt", "text/plain", legacy.toByteArray(charset("windows-1252"))) {
            assertEquals(legacy, reader.read(it).text)
        }
        withDocument("txt", "text/plain", "∑ 😀 𐐀".toByteArray(Charsets.UTF_8)) {
            assertEquals("∑ 😀 𐐀", reader.read(it).text)
        }
        withDocument("txt", "text/plain", byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0xff.toByte())) {
            assertThrows(IllegalArgumentException::class.java) { reader.read(it) }
        }
    }

    @Test fun officeExtractionPreservesRunsTabsLineBreaksAndPresentationOrder() {
        val first = "  Fórmula ∑ n\u0303"
        val second = "漢字 😀 𐐀  "
        val word = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p>
                <w:r><w:t xml:space="preserve">$first</w:t><w:tab/><w:t xml:space="preserve">$second</w:t></w:r>
                <w:r><w:br/><w:t>Último</w:t></w:r>
              </w:p></w:body>
            </w:document>
        """.trimIndent()
        withDocument("docx", StudyDocumentReader.MIME_TYPES[2], officeZip("word/document.xml" to word)) {
            assertEquals("$first\t$second\nÚltimo\n\n", reader.read(it).text)
        }

        val presentation = """
            <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:sldIdLst><p:sldId id="257" r:id="rId2"/><p:sldId id="256" r:id="rId1"/></p:sldIdLst>
            </p:presentation>
        """.trimIndent()
        val relationships = """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Target="slides/slide1.xml"/><Relationship Id="rId2" Target="slides/slide2.xml"/>
            </Relationships>
        """.trimIndent()
        fun slide(text: String) = """
            <a:p xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:r><a:t>$text</a:t></a:r></a:p>
        """.trimIndent()
        val archive = officeZip(
            "ppt/slides/slide1.xml" to slide(second),
            "ppt/slides/slide2.xml" to slide(first),
            "ppt/presentation.xml" to presentation,
            "ppt/_rels/presentation.xml.rels" to relationships
        )
        withDocument("pptx", StudyDocumentReader.MIME_TYPES[3], archive) {
            assertEquals("$first\n\n$second\n\n", reader.read(it).text)
        }
    }

    @Test fun pdfExtractionRetainsAccentsAndSeparatesLines() {
        PDFBoxResourceLoader.init(context)
        val first = "Cañón, cálculo y energía."
        val second = "Otra línea con acción."
        val pdf = PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(48f, 740f)
                content.showText(first)
                content.newLineAtOffset(0f, -20f)
                content.showText(second)
                content.endText()
            }
            ByteArrayOutputStream().apply { document.save(this) }.toByteArray()
        }
        withDocument("pdf", "application/pdf", pdf) {
            val result = reader.read(it).text
            assertEquals(listOf(first, second), result.lineSequence().filter(String::isNotBlank).toList())
            assertTrue(result.contains("$first\n"))
        }
    }

    private fun officeZip(vararg parts: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, xml) in parts) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun withDocument(extension: String, mimeType: String, bytes: ByteArray, check: (Uri) -> Unit) {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "unicode-${UUID.randomUUID()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            check(uri)
        } finally {
            resolver.delete(uri, null, null)
        }
    }
}
