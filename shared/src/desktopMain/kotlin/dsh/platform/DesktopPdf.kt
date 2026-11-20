package dsh.platform

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

fun renderPdfDesktop(bytes: ByteArray, maxPages: Int, widthPx: Int): List<ByteArray> =
    Loader.loadPDF(bytes).use { doc ->
        val r = PDFRenderer(doc)
        (0 until minOf(doc.numberOfPages, maxPages)).map { i ->
            val pageW = doc.getPage(i).mediaBox.width
            val img = r.renderImage(i, (widthPx / pageW).coerceIn(0.5f, 4f))
            ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
        }
    }
