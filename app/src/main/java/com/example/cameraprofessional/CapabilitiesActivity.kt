package com.example.cameraprofessional

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraprofessional.databinding.ActivityCapabilitiesBinding
import org.json.JSONArray
import java.util.concurrent.Executors

/**
 * CapabilitiesActivity — full spec sheet for every camera on this device.
 * Reads from cache instantly if already scanned; scans in background if not.
 * Never blocks MainActivity's capture flow — this is a totally separate screen.
 */
class CapabilitiesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCapabilitiesBinding
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCapabilitiesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.specsText.text = "Scanning camera hardware..."

        executor.execute {
            val data = CameraCapabilities.loadCached(this)
                ?: CameraCapabilities.scanAndCache(this)
            val formatted = formatReport(data)
            runOnUiThread { binding.specsText.text = formatted }
        }
    }

    private fun formatReport(array: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until array.length()) {
            val cam = array.getJSONObject(i)
            sb.append("━━━━━━━━━━━━━━━━━━━━\n")
            sb.append("Camera ID: ${cam.optString("cameraId")}\n")
            sb.append("Facing: ${cam.optString("facing")}\n")
            sb.append("Lens type: ${cam.optString("lensTypeGuess")}\n")
            sb.append("Sensor resolution: ${cam.optString("sensorResolution")}\n")
            sb.append("Megapixels: ${"%.1f".format(cam.optDouble("megapixels"))} MP\n")
            sb.append("Sensor size: ${cam.optJSONObject("sensorSizeMM")}\n")
            sb.append("Focal lengths (mm): ${cam.optJSONArray("focalLengthsMM")}\n")
            sb.append("Apertures: ${cam.optJSONArray("apertures")}\n")
            sb.append("Horizontal FOV (deg): ${cam.optJSONArray("horizontalFOVDegrees")}\n")
            sb.append("OIS: ${cam.optBoolean("opticalStabilization")}\n")
            sb.append("Video digital stabilization: ${cam.optBoolean("videoDigitalStabilization")}\n")
            sb.append("Autofocus: ${cam.optBoolean("hasAutofocus")} (${cam.optJSONArray("autofocusModes")})\n")
            sb.append("Flash: ${cam.optBoolean("flashAvailable")}\n")
            sb.append("JPEG: ${cam.optBoolean("jpegSupport")}  HEIF: ${cam.optBoolean("heifSupport")}  RAW: ${cam.optBoolean("rawSupport")}\n")
            sb.append("HDR support: ${cam.optBoolean("hdrSupport")}\n")
            sb.append("Manual sensor control: ${cam.optBoolean("manualSensorControl")}\n")
            sb.append("Zoom range: ${cam.optString("zoomRange")}  Max digital zoom: ${cam.optDouble("maxDigitalZoom")}\n")
            sb.append("Exposure comp range: ${cam.optString("exposureCompensationRange")}\n")
            sb.append("ISO range: ${cam.optString("isoRange")}\n")
            sb.append("White balance modes: ${cam.optJSONArray("whiteBalanceModes")}\n")
            sb.append("Supported FPS ranges: ${cam.optJSONArray("supportedFpsRanges")}\n")
            sb.append("Slow motion: ${cam.optBoolean("slowMotionSupport")} (max ${cam.optInt("slowMotionMaxFps")} fps)\n")
            sb.append("Supported photo resolutions: ${cam.optJSONArray("supportedPhotoResolutions")}\n")
            sb.append("\n")
        }
        return sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
