package com.example.cameraprofessional

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraprofessional.databinding.ActivityDiagnosticsBinding

/**
 * DiagnosticsActivity — read-only performance + config report.
 * Lives under Settings (per your requirement), not as a main camera mode.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val profile = CameraOptimizer.loadCachedProfile(this)
        val avgLatency = PerformanceMonitor.averageCaptureLatencyMs()
        val lastLatency = PerformanceMonitor.lastCaptureLatencyMs()
        val samples = PerformanceMonitor.sampleCount()

        val report = buildString {
            append("PERFORMANCE ENGINE\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            append("Last shutter-to-saved latency: ${if (lastLatency >= 0) "$lastLatency ms" else "no data yet"}\n")
            append("Average latency (last $samples shots): ${if (avgLatency >= 0) "$avgLatency ms" else "no data yet"}\n")
            append("\nNote: latency here is save-to-disk time via the background\n")
            append("executor — it never blocks the shutter button itself.\n")
            append("\n\nACTIVE OPTIMIZED PROFILE\n")
            append("━━━━━━━━━━━━━━━━━━━━\n")
            if (profile != null) {
                append("Cameras: ${profile.cameraCount} (${profile.rearLensCount} rear, ${profile.frontLensCount} front)\n")
                append("Macro lens: ${profile.hasMacroLens}   Telephoto lens: ${profile.hasTelephotoLens}\n")
                append("Photo resolution: ${profile.recommendedPhotoResolution}\n")
                append("Video quality: ${profile.recommendedVideoQuality} @ ${profile.recommendedFps}fps\n")
                append("Stabilization: ${profile.useStabilization}\n")
                append("HDR: ${profile.useHdr}\n")
                append("RAW available: ${profile.hasRaw}\n")
                append("AF mode: ${profile.afMode}\n")
            } else {
                append("Not optimized yet — run ⚡ Optimize Camera first.\n")
            }
        }

        binding.diagnosticsText.text = report
    }
}
