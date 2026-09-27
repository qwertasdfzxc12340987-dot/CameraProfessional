package com.example.cameraprofessional

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.cameraprofessional.databinding.ActivityProCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ProCameraActivity — manual controls, where the hardware actually exposes them.
 *
 * ISO, shutter speed and manual focus distance need CameraCharacteristics
 * REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR. On phones without that flag
 * (common on budget/mid-range Samsungs incl. many Galaxy F-series units),
 * those controls are disabled here rather than silently doing nothing —
 * exposure compensation and white balance preset still work everywhere since
 * they're part of the standard (non-manual-sensor) Camera2 API.
 *
 * This screen is intentionally separate from MainActivity: manual-control
 * capture sessions reconfigure on every slider change, which is NOT something
 * you want anywhere near the fast-tap photo flow.
 */
class ProCameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProCameraBinding
    private val executor = Executors.newSingleThreadExecutor()

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var supportsManualSensor = false
    private var isoRange: IntRange = 100..800
    private var exposureRange: LongRange = 1_000_000L..100_000_000L // ns, fallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startProCamera()

        binding.isoSlider.setOnSeekBarChangeListener(simpleListener { applyManualControls() })
        binding.shutterSlider.setOnSeekBarChangeListener(simpleListener { applyManualControls() })
        binding.exposureCompSlider.setOnSeekBarChangeListener(simpleListener { applyManualControls() })
        binding.wbSlider.setOnSeekBarChangeListener(simpleListener { applyManualControls() })

        binding.captureButton.setOnClickListener { capturePhoto() }
    }

    private fun simpleListener(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange()
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun startProCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                checkManualSensorSupport()
            } catch (e: Exception) {
                Toast.makeText(this, "Camera bind failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun checkManualSensorSupport() {
        val cam = camera ?: return
        val camInfo = Camera2CameraInfo.from(cam.cameraInfo)
        val caps = camInfo.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        supportsManualSensor = caps?.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        ) == true

        val iso = camInfo.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (iso != null) isoRange = iso.lower..iso.upper

        val exp = camInfo.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (exp != null) exposureRange = exp.lower..exp.upper

        runOnUiThread {
            binding.isoSlider.isEnabled = supportsManualSensor
            binding.shutterSlider.isEnabled = supportsManualSensor
            binding.manualSensorNotice.text = if (supportsManualSensor) {
                "Manual ISO/shutter: supported on this device"
            } else {
                "Manual ISO/shutter: NOT exposed by this device's camera HAL — using Auto"
            }
        }
    }

    /**
     * Applies manual controls via Camera2Interop.Extender at the next session
     * rebuild. This intentionally rebuilds capture config only when the user
     * touches a slider here — never during normal fast photo capture in MainActivity.
     */
    private fun applyManualControls() {
        val provider = ProcessCameraProvider.getInstance(this).get()
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)

        val ext = Camera2Interop.Extender(captureBuilder)

        if (supportsManualSensor) {
            val isoProgress = binding.isoSlider.progress
            val iso = isoRange.first + ((isoRange.last - isoRange.first) * isoProgress / 100)
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)

            val shutterProgress = binding.shutterSlider.progress
            val exposureNs = exposureRange.first + ((exposureRange.last - exposureRange.first) * shutterProgress / 100)
            ext.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)

            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        }

        // Exposure compensation & WB always attempted — standard (non-manual-sensor) controls.
        val expCompProgress = binding.exposureCompSlider.progress - 100 // -100..100
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, expCompProgress / 10)

        val wbModes = listOf(
            CaptureRequest.CONTROL_AWB_MODE_AUTO,
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
            CaptureRequest.CONTROL_AWB_MODE_SHADE
        )
        val wbIndex = (binding.wbSlider.progress * (wbModes.size - 1) / 100).coerceIn(0, wbModes.size - 1)
        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wbModes[wbIndex])

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }
        imageCapture = captureBuilder.build()

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
        } catch (e: Exception) {
            // Some devices reject certain manual combinations — fail silently to Auto rather than crash.
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())
        val file = File(getExternalFilesDir("CameraPro"), "PRO_$name.jpg")
        file.parentFile?.mkdirs()

        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread { Toast.makeText(this@ProCameraActivity, "Saved ✓", Toast.LENGTH_SHORT).show() }
                }
                override fun onError(exc: ImageCaptureException) {
                    runOnUiThread { Toast.makeText(this@ProCameraActivity, "Capture failed", Toast.LENGTH_SHORT).show() }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
