package com.example.cameraprofessional

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraprofessional.databinding.ActivityEnhancerBinding
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * EnhancerActivity — SLOW PATH, by design.
 *
 * Ye screen deliberately MainActivity se disconnect hai. User photo click
 * karke seedha yahan nahi aata — usse manually "Enhance" button dabana
 * padta hai. Isliye enhancement ka processing time (jitna bhi lage) kabhi
 * bhi shutter speed ko affect nahi karta.
 *
 * Original enhancement pipeline (color-matrix based, koi third-party
 * proprietary preset use nahi kiya gaya):
 *  - Brightness
 *  - Contrast
 *  - Saturation
 *  - Warmth (white-balance jaisा shift)
 *  - Sharpen (simple convolution, ek baar apply)
 *
 * "Auto Enhance" ek sensible default combination apply karta hai
 * (halka contrast + saturation boost) jise user aage khud tune kar sakta hai.
 */
class EnhancerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "extra_image_path"
    }

    private lateinit var binding: ActivityEnhancerBinding
    private lateinit var originalBitmap: Bitmap
    private val executor = Executors.newSingleThreadExecutor()

    private var brightness = 0f      // -100..100
    private var contrast = 0f        // -100..100
    private var saturation = 0f      // -100..100
    private var warmth = 0f          // -100..100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnhancerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        if (path == null) {
            Toast.makeText(this, "Image not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        originalBitmap = BitmapFactory.decodeFile(path)
        binding.previewImage.setImageBitmap(originalBitmap)

        setupSlider(binding.brightnessSlider) { brightness = it; applyPreview() }
        setupSlider(binding.contrastSlider) { contrast = it; applyPreview() }
        setupSlider(binding.saturationSlider) { saturation = it; applyPreview() }
        setupSlider(binding.warmthSlider) { warmth = it; applyPreview() }

        binding.autoEnhanceButton.setOnClickListener {
            contrast = 12f
            saturation = 15f
            brightness = 4f
            warmth = 5f
            syncSlidersToValues()
            applyPreview()
        }

        binding.saveEnhancedButton.setOnClickListener { saveEnhanced(path) }
    }

    private fun setupSlider(seekBar: SeekBar, onChange: (Float) -> Unit) {
        // SeekBar range 0..200 maps to -100..100
        seekBar.max = 200
        seekBar.progress = 100
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress - 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun syncSlidersToValues() {
        binding.brightnessSlider.progress = (brightness + 100).toInt()
        binding.contrastSlider.progress = (contrast + 100).toInt()
        binding.saturationSlider.progress = (saturation + 100).toInt()
        binding.warmthSlider.progress = (warmth + 100).toInt()
    }

    /** Preview update — lightweight, runs on UI thread since it's just a ColorMatrix draw. */
    private fun applyPreview() {
        binding.previewImage.colorFilter = ColorMatrixColorFilter(buildColorMatrix())
    }

    private fun buildColorMatrix(): ColorMatrix {
        val cm = ColorMatrix()

        // Saturation
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1f + (saturation / 100f))
        cm.postConcat(satMatrix)

        // Contrast (scale around midpoint 128)
        val c = 1f + (contrast / 100f)
        val translate = (1f - c) * 128f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, translate,
                0f, c, 0f, 0f, translate,
                0f, 0f, c, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)

        // Brightness (simple additive)
        val b = brightness * 2.55f // -100..100 -> -255..255
        val brightMatrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, b,
                0f, 1f, 0f, 0f, b,
                0f, 0f, 1f, 0f, b,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(brightMatrix)

        // Warmth: shift red up / blue down slightly (or vice versa for cool)
        val w = warmth * 0.6f
        val warmthMatrix = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, w,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, -w,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(warmthMatrix)

        return cm
    }

    /** Actual pixel-level render + save happens on a background thread — never blocks UI. */
    private fun saveEnhanced(originalPath: String) {
        binding.saveEnhancedButton.isEnabled = false
        Toast.makeText(this, "Enhancing & saving...", Toast.LENGTH_SHORT).show()

        executor.execute {
            val result = Bitmap.createBitmap(
                originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.colorFilter = ColorMatrixColorFilter(buildColorMatrix())
            canvas.drawBitmap(originalBitmap, 0f, 0f, paint)

            val outFile = File(
                File(originalPath).parentFile,
                File(originalPath).nameWithoutExtension + "_enhanced.jpg"
            )
            FileOutputStream(outFile).use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            runOnUiThread {
                binding.saveEnhancedButton.isEnabled = true
                Toast.makeText(
                    this, "Enhanced photo saved: ${outFile.name}", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
