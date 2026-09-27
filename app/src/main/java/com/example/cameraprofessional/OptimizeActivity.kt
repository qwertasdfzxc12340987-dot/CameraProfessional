package com.example.cameraprofessional

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraprofessional.databinding.ActivityOptimizeBinding
import java.util.concurrent.Executors

/**
 * OptimizeActivity — "SCAN COMPLETE ✓" summary + one big ⚡ OPTIMIZE CAMERA button.
 *
 * Flow:
 * 1. On open, run/read the capability scan (background thread).
 * 2. Show a short summary: camera count, rear/front split, 4K/HDR/stabilization/RAW flags.
 * 3. User taps OPTIMIZE -> CameraOptimizer decides + caches the best pipeline config.
 * 4. MainActivity (and other capture screens) read this cached profile instead of
 *    guessing defaults or re-scanning hardware every launch.
 */
class OptimizeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOptimizeBinding
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOptimizeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.summaryText.text = "Scanning camera hardware..."
        binding.optimizeButton.isEnabled = false

        executor.execute {
            val caps = CameraCapabilities.loadCached(this)
                ?: CameraCapabilities.scanAndCache(this)

            var rear = 0; var front = 0
            var has4K = false; var hasHdr = false; var hasStab = false; var hasRaw = false
            for (i in 0 until caps.length()) {
                val cam = caps.getJSONObject(i)
                if (cam.optString("facing") == "FRONT") front++ else rear++
                if (cam.optBoolean("hdrSupport")) hasHdr = true
                if (cam.optBoolean("opticalStabilization") || cam.optBoolean("videoDigitalStabilization")) hasStab = true
                if (cam.optBoolean("rawSupport")) hasRaw = true
                val resList = cam.optJSONArray("supportedPhotoResolutions")
                if (resList != null) {
                    for (idx in 0 until resList.length()) {
                        val parts = resList.optString(idx).split("x")
                        if (parts.size == 2) {
                            val w = parts[0].toIntOrNull() ?: 0
                            val h = parts[1].toIntOrNull() ?: 0
                            if (w >= 3840 && h >= 2160) has4K = true
                        }
                    }
                }
            }

            val summary = buildString {
                append("SCAN COMPLETE ✓\n\n")
                append("${caps.length()} Cameras detected\n")
                append("$rear rear lens${if (rear != 1) "es" else ""} + $front front camera${if (front != 1) "s" else ""}\n")
                if (has4K) append("4K video available\n")
                if (hasHdr) append("HDR available\n")
                if (hasStab) append("Stabilization available\n")
                if (hasRaw) append("RAW available\n")
            }

            runOnUiThread {
                binding.summaryText.text = summary
                binding.optimizeButton.isEnabled = true
            }
        }

        binding.optimizeButton.setOnClickListener {
            binding.optimizeButton.isEnabled = false
            binding.optimizeButton.text = "Optimizing..."
            executor.execute {
                val profile = CameraOptimizer.runOptimization(this)
                runOnUiThread {
                    binding.summaryText.append(
                        "\n\n⚡ Optimized:\n" +
                            "Photo resolution: ${profile.recommendedPhotoResolution}\n" +
                            "Video quality: ${profile.recommendedVideoQuality} @ ${profile.recommendedFps}fps\n" +
                            "Stabilization: ${if (profile.useStabilization) "ON" else "OFF"}\n" +
                            "HDR: ${if (profile.useHdr) "ON" else "OFF"}\n" +
                            "AF mode: ${profile.afMode}"
                    )
                    binding.optimizeButton.text = "✓ Optimized"
                    setResult(RESULT_OK)
                }
            }
        }

        binding.doneButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
