package com.example.cameraprofessional

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.cameraprofessional.databinding.ActivityDocumentScannerBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * DocumentScannerActivity — "documentation type" scanner.
 *
 * Flow: Capture -> tap 4 corners of the page -> perspective-warp to a clean
 * rectangle -> choose Black & White (text-document look) or Color/Enhanced ->
 * add as a page -> repeat for more pages -> Export all pages as one PDF.
 *
 * All warping/filtering happens on a background executor; the camera preview
 * and shutter stay on the fast path exactly like MainActivity.
 */
class DocumentScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentScannerBinding
    private val executor = Executors.newSingleThreadExecutor()

    private var imageCapture: ImageCapture? = null
    private var rawBitmap: Bitmap? = null
    private var processedBitmap: Bitmap? = null
    private var isBlackAndWhite = true
    private val pages = mutableListOf<Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startCamera()

        binding.captureButton.setOnClickListener { capturePhoto() }
        binding.resetPointsButton.setOnClickListener { binding.cornerOverlay.reset() }
        binding.applyCropButton.setOnClickListener { applyPerspectiveCrop() }
        binding.bwToggleButton.setOnClickListener {
            isBlackAndWhite = !isBlackAndWhite
            binding.bwToggleButton.text = if (isBlackAndWhite) "Mode: B/W" else "Mode: Color"
            processedBitmap?.let { applyFilterAndShow(it) }
        }
        binding.addPageButton.setOnClickListener { addCurrentPageAndReset() }
        binding.exportPdfButton.setOnClickListener { exportPdf() }
        updatePageCount()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val file = File(getExternalFilesDir("CameraPro/Docs"), "SCAN_$name.jpg")
        file.parentFile?.mkdirs()

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    rawBitmap = bmp
                    runOnUiThread {
                        binding.previewImage.setImageBitmap(bmp)
                        binding.previewImage.visibility = android.view.View.VISIBLE
                        binding.cornerOverlay.reset()
                        Toast.makeText(this@DocumentScannerActivity, "Now tap 4 corners of the page", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onError(exc: ImageCaptureException) {}
            }
        )
    }

    private fun applyPerspectiveCrop() {
        val src = rawBitmap ?: return
        val pts = binding.cornerOverlay.points
        if (pts.size != 4) {
            Toast.makeText(this, "Tap exactly 4 corners first", Toast.LENGTH_SHORT).show()
            return
        }

        // Map overlay-view coordinates -> bitmap pixel coordinates (view may be scaled to fit).
        val viewW = binding.previewImage.width.toFloat()
        val viewH = binding.previewImage.height.toFloat()
        val scaleX = src.width / viewW
        val scaleY = src.height / viewH

        val srcPts = FloatArray(8)
        pts.forEachIndexed { i, (x, y) ->
            srcPts[i * 2] = x * scaleX
            srcPts[i * 2 + 1] = y * scaleY
        }

        // Output rectangle size = bounding box of the tapped quad.
        val outW = src.width
        val outH = src.height
        val dstPts = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())

        executor.execute {
            val matrix = Matrix()
            matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
            val warped = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(warped)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(src, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

            processedBitmap = warped
            runOnUiThread { applyFilterAndShow(warped) }
        }
    }

    private fun applyFilterAndShow(bitmap: Bitmap) {
        val result = if (isBlackAndWhite) toDocumentBlackAndWhite(bitmap) else enhanceColorDocument(bitmap)
        binding.previewImage.setImageBitmap(result)
        binding.cornerOverlay.visibility = android.view.View.GONE
    }

    /** Grayscale + contrast boost — classic "scanned document" look. */
    private fun toDocumentBlackAndWhite(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        val contrast = 1.4f
        val translate = (1f - contrast) * 128f
        val contrastMatrix = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    /** Mild contrast + saturation boost for a clean "color document" look. */
    private fun enhanceColorDocument(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(1.15f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun addCurrentPageAndReset() {
        val current = processedBitmap ?: run {
            Toast.makeText(this, "Crop a page first", Toast.LENGTH_SHORT).show()
            return
        }
        val finalBitmap = if (isBlackAndWhite) toDocumentBlackAndWhite(current) else enhanceColorDocument(current)
        pages.add(finalBitmap)
        updatePageCount()

        rawBitmap = null
        processedBitmap = null
        binding.previewImage.setImageBitmap(null)
        binding.previewImage.visibility = android.view.View.GONE
        binding.cornerOverlay.visibility = android.view.View.VISIBLE
        binding.cornerOverlay.reset()
        Toast.makeText(this, "Page ${pages.size} added", Toast.LENGTH_SHORT).show()
    }

    private fun updatePageCount() {
        binding.pageCountText.text = "${pages.size} page(s) added"
    }

    private fun exportPdf() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Add at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        executor.execute {
            val pdf = PdfDocument()
            pages.forEachIndexed { index, bmp ->
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, index + 1).create()
                val page = pdf.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                pdf.finishPage(page)
            }
            val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(System.currentTimeMillis())
            val outFile = File(getExternalFilesDir("CameraPro/Docs"), "Document_$name.pdf")
            FileOutputStream(outFile).use { pdf.writeTo(it) }
            pdf.close()

            runOnUiThread {
                Toast.makeText(this, "PDF saved: ${outFile.name}", Toast.LENGTH_LONG).show()
                pages.clear()
                updatePageCount()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
