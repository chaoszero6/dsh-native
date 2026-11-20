package dsh.platform

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File

/** Renders PDF pages to PNG using the platform PdfRenderer. */
fun renderPdfAndroid(cacheDir: File, bytes: ByteArray, maxPages: Int, widthPx: Int): List<ByteArray> {
    val f = File.createTempFile("preview", ".pdf", cacheDir)
    try {
        f.writeBytes(bytes)
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                return (0 until minOf(r.pageCount, maxPages)).map { i ->
                    r.openPage(i).use { p ->
                        val h = (widthPx.toFloat() * p.height / p.width).toInt()
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); bmp.recycle() }.toByteArray()
                    }
                }
            }
        }
    } finally { f.delete() }
}
