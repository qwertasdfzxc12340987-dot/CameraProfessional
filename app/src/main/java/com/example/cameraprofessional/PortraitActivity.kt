package com.example.cameraprofessional

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.cameraprofessional.databinding.ActivityPortraitBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.Segmentation
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * PortraitActivity — capture-then-process portrait blur.
 *
 * IMPORTANT HONESTY NOTE: true real-time live-preview background blur (like
 * stock Samsung Portrait mode) needs GPU frame compositing at 30fps, which is
 * a much bigger lift than fits here. This pipeline instead:
 *   1. Captures a full-quality photo through the normal fast path
 *   2. Runs on-device ML Kit selfie segmentation ONCE on that captured photo
 *   3. Applies a background blur based on the segmentation mask
 *   4. Lets you adjust blur strength and re-render before saving
 *
 * This still keeps the SHUTTER itself fast (same MINIMIZE_LATENCY capture as
 * MainActivity) — the segmentation/blur work happens only after, in this
 * separate activity, on a background thread.
 */
class PortraitActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPortraitBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null
    private var blurStrength = 12 // 0..30 box-blur radius equivalent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPortraitBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startCamera()

        binding.captureButton.setOnClickListener { capturePhoto() }
        binding.blurSlider.max = 30
        binding.blurSlider.progress = blurStrength
        binding.blurSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    blurStrength = progress
                    capturedBitmap?.let { runPortraitPipeline(it) }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.saveButton.setOnClickListener { saveResult() }
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val file = File(getExternalFilesDir("CameraPro"), "PORTRAIT_RAW_$name.jpg")
        file.parentFile?.mkdirs()

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    capturedBitmap = bmp
                    runOnUiThread { binding.resultImage.setImageBitmap(bmp) }
                    runPortraitPipeline(bmp)
                }
                override fun onError(exc: ImageCaptureException) {}
            }
        )
    }

    private fun runPortraitPipeline(source: Bitmap) {
        binding.statusText.text = "Detecting subject..."
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        val segmenter = Segmentation.getClient(options)
        val input = InputImage.fromBitmap(source, 0)

        segmenter.process(input)
            .addOnSuccessListener { mask ->
                executor.execute {
                    val result = compositeBlur(source, mask.buffer, mask.width, mask.height, blurStrength)
                    runOnUiThread {
                        binding.resultImage.setImageBitmap(result)
                        binding.statusText.text = "Portrait ready"
                    }
                }
            }
            .addOnFailureListener {
                runOnUiThread { binding.statusText.text = "Segmentation failed: ${it.message}" }
            }
    }

    /** Blends a blurred copy of [source] behind the foreground, using the ML Kit confidence mask. */
    private fun compositeBlur(
        source: Bitmap,
        maskBuffer: java.nio.ByteBuffer,
        maskWidth: Int,
        maskHeight: Int,
        radius: Int
    ): Bitmap {
        val blurred = fastApproximateBlur(source, radius)
        val output = source.copy(Bitmap.Config.ARGB_8888, true)

        maskBuffer.rewind()
        val confidences = FloatArray(maskWidth * maskHeight)
        maskBuffer.asFloatBuffer().get(confidences)

        val scaleX = maskWidth.toFloat() / source.width
        val scaleY = maskHeight.toFloat() / source.height

        val sharpPixels = IntArray(source.width * source.height)
        val blurPixels = IntArray(source.width * source.height)
        source.getPixels(sharpPixels, 0, source.width, 0, 0, source.width, source.height)
        blurred.getPixels(blurPixels, 0, source.width, 0, 0, source.width, source.height)

        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val mx = (x * scaleX).toInt().coerceIn(0, maskWidth - 1)
                val my = (y * scaleY).toInt().coerceIn(0, maskHeight - 1)
                val confidence = confidences[my * maskWidth + mx] // 1.0 = foreground/person
                val idx = y * source.width + x
                sharpPixels[idx] = blendPixel(sharpPixels[idx], blurPixels[idx], confidence)
            }
        }
        output.setPixels(sharpPixels, 0, source.width, 0, 0, source.width, source.height)
        return output
    }

    private fun blendPixel(sharp: Int, blur: Int, foregroundConfidence: Float): Int {
        val a = foregroundConfidence.coerceIn(0f, 1f)
        val r = (Color.red(sharp) * a + Color.red(blur) * (1 - a)).toInt()
        val g = (Color.green(sharp) * a + Color.green(blur) * (1 - a)).toInt()
        val b = (Color.blue(sharp) * a + Color.blue(blur) * (1 - a)).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    /** Cheap blur: downscale, upscale with bilinear filtering. Fast enough for repeated slider drags. */
    private fun fastApproximateBlur(src: Bitmap, radius: Int): Bitmap {
        val factor = (radius.coerceIn(1, 30) / 2).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(
            src, (src.width / factor).coerceAtLeast(1), (src.height / factor).coerceAtLeast(1), true
        )
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    private fun saveResult() {
        val bmp = (binding.resultImage.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val file = File(getExternalFilesDir("CameraPro"), "PORTRAIT_$name.jpg")
        executor.execute {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            runOnUiThread { Toast.makeText(this, "Saved ✓", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
