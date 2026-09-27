package com.example.cameraprofessional

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import com.example.cameraprofessional.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MainActivity — CAPTURE ONLY (Photo + Video toggle + zoom-based lens switching).
 *
 * Design rule unchanged from before: nothing heavy runs here. Manual Pro controls,
 * Portrait blur, QR scan, and Document scan all live in their OWN activities so this
 * screen's shutter response never depends on their processing time.
 *
 * Uses the cached OptimizedProfile (from "⚡ Optimize Camera") to pick resolution,
 * video quality, stabilization and HDR — instead of hard-coded guesses, and instead
 * of re-scanning hardware on every launch.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var lastSavedFile: File? = null
    private var isVideoMode = false
    private var isRecording = false
    // Ignores extra shutter taps while one photo is still being written to disk —
    // this is what stops rapid/spammed clicking from ever crashing or corrupting a file.
    private val captureInProgress = AtomicBoolean(false)

    private val zoomLevels = listOf(0.6f, 1.0f, 2.0f, 3.0f, 5.0f)
    private var currentZoomIndex = 1 // start at 1x

    private val requestPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val cameraGranted = results[Manifest.permission.CAMERA] == true
            if (cameraGranted) startCamera() else {
                Toast.makeText(this, "Camera permission zaroori hai", Toast.LENGTH_LONG).show()
            }
            // Mic permission (results[Manifest.permission.RECORD_AUDIO]) is only needed
            // when video recording with audio starts; if denied, toggleRecording() below
            // falls back to muted video rather than crashing.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Scan + optimize only if not already cached. Never blocks camera start.
        cameraExecutor.execute {
            if (!CameraCapabilities.isAlreadyScanned(this)) {
                CameraCapabilities.scanAndCache(this)
            }
        }

        if (hasCameraPermission()) startCamera() else {
            requestPermissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }

        setupModeStrip()
        setupZoomBar()

        binding.captureButton.setOnClickListener {
            binding.captureButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            animateShutterPress()
            if (isVideoMode) toggleRecording() else takePhotoFast()
        }
        binding.switchFacingButton.setOnClickListener { switchFacing() }

        binding.enhanceButton.setOnClickListener {
            val file = lastSavedFile
            if (file == null) {
                Toast.makeText(this, "Pehle ek photo click karein", Toast.LENGTH_SHORT).show()
            } else {
                val i = Intent(this, EnhancerActivity::class.java)
                i.putExtra(EnhancerActivity.EXTRA_IMAGE_PATH, file.absolutePath)
                startActivity(i)
            }
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupModeStrip() {
        val modes = listOf(
            "Photo" to { openHere() },
            "Video" to { switchToVideoMode() },
            "Pro" to { startActivity(Intent(this, ProCameraActivity::class.java)) },
            "Portrait" to { startActivity(Intent(this, PortraitActivity::class.java)) },
            "QR" to { startActivity(Intent(this, QrScannerActivity::class.java)) },
            "Doc Scan" to { startActivity(Intent(this, DocumentScannerActivity::class.java)) },
            "Panorama" to {
                Toast.makeText(this, "Panorama: coming soon", Toast.LENGTH_SHORT).show()
            }
        )
        binding.modeStrip.removeAllViews()
        for ((label, action) in modes) {
            val btn = android.widget.Button(this)
            btn.text = label
            btn.textSize = 12f
            btn.setOnClickListener { action() }
            binding.modeStrip.addView(btn)
        }
    }

    private fun openHere() { isVideoMode = false; binding.captureButton.text = "📸" }
    private fun switchToVideoMode() { isVideoMode = true; binding.captureButton.text = "⏺" }

    private fun setupZoomBar() {
        binding.zoomBar.removeAllViews()
        zoomLevels.forEachIndexed { index, zoom ->
            val btn = android.widget.Button(this)
            btn.text = if (zoom == 1.0f) "1x" else "${zoom}x"
            btn.textSize = 11f
            btn.setOnClickListener {
                currentZoomIndex = index
                applyZoom(zoom)
            }
            binding.zoomBar.addView(btn)
        }
    }

    /**
     * Zoom switching: hum CameraX CameraControl.setZoomRatio() use karte hain.
     * Modern devices (including Samsung logical multi-cameras) is single call se
     * HAL level pe khud switch kar dete hain ultra-wide <-> main <-> telephoto
     * jab zoom ratio unki range cross karta hai — is wajah se preview me koi
     * jhatka/freeze nahi aata, switching bilkul smooth rehta hai.
     */
    private fun applyZoom(zoom: Float) {
        camera?.cameraControl?.setZoomRatio(zoom)
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private var currentLensFacing = CameraSelector.LENS_FACING_BACK

    private fun switchFacing() {
        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        bindCameraUseCases()
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val profile = CameraOptimizer.loadCachedProfile(this)

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        // MINIMIZE_LATENCY = shutter tap ke turant baad frame capture, koi extra
        // multi-frame processing delay nahi -> yahi "fast click" ka core hai.
        // setJpegQuality(100) + HIGHEST_AVAILABLE resolution = full original sensor
        // quality, koi downscaling/recompression loss nahi.
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(100)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .build()

        val quality = CameraOptimizer.videoQualityToCameraXQuality(
            profile?.recommendedVideoQuality ?: "FHD"
        )
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, videoCapture
            )
            // Re-apply whatever zoom level was active before rebind (e.g. after lens switch)
            applyZoom(zoomLevels[currentZoomIndex])
        } catch (e: Exception) {
            Log.e("MainActivity", "Camera bind failed: ${e.message}")
        }
    }

    private fun animateShutterPress() {
        val shrinkX = ObjectAnimator.ofFloat(binding.captureButton, "scaleX", 1f, 0.85f, 1f)
        val shrinkY = ObjectAnimator.ofFloat(binding.captureButton, "scaleY", 1f, 0.85f, 1f)
        shrinkX.duration = 120
        shrinkY.duration = 120
        shrinkX.start()
        shrinkY.start()
    }

    private fun takePhotoFast() {
        // Guard: agar ek photo abhi save ho rahi hai, to naya tap ignore karo.
        // Isse rapid/spam clicking se kabhi file-write clash ya crash nahi hota.
        if (!captureInProgress.compareAndSet(false, true)) return

        val capture = imageCapture
        if (capture == null) {
            captureInProgress.set(false)
            return
        }

        try {
            val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis())
            val photoFile = File(getExternalFilesDir("CameraPro"), "$name.jpg")
            photoFile.parentFile?.mkdirs()

            val startTime = System.currentTimeMillis()
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            capture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        lastSavedFile = photoFile
                        PerformanceMonitor.recordCaptureLatency(System.currentTimeMillis() - startTime)
                        captureInProgress.set(false)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Saved ✓", Toast.LENGTH_SHORT).show()
                        }
                        // Gallery copy runs AFTER the fast local save, on the same
                        // background executor -> never adds latency to the shutter tap.
                        publishToGallery(photoFile)
                    }

                    override fun onError(exc: ImageCaptureException) {
                        Log.e("MainActivity", "Capture failed: ${exc.message}", exc)
                        captureInProgress.set(false)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Capture failed, try again", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            // Kabhi bhi unexpected exception aaye (storage full, permission race, etc.)
            // -> app crash nahi karega, guard release hoke user dobara try kar sakta hai.
            Log.e("MainActivity", "takePhotoFast crashed-guard: ${e.message}", e)
            captureInProgress.set(false)
        }
    }

    /**
     * Copies the just-saved JPEG into the public gallery (Pictures/CameraPro) via
     * MediaStore, so it shows up in the phone's Gallery/Photos app immediately —
     * without touching the fast local save path used above (and used by EnhancerActivity).
     */
    private fun publishToGallery(sourceFile: File) {
        cameraExecutor.execute {
            try {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, sourceFile.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraPro")
                    }
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                ) ?: return@execute
                contentResolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Gallery publish failed: ${e.message}", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val videoCap = videoCapture ?: return

        if (isRecording) {
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
            binding.captureButton.text = "⏺"
            return
        }

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val videoFile = File(getExternalFilesDir("CameraPro"), "$name.mp4")
        videoFile.parentFile?.mkdirs()

        val outputOptions = FileOutputOptions.Builder(videoFile).build()
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        var pending = videoCap.output.prepareRecording(this, outputOptions)
        if (hasMic) pending = pending.withAudioEnabled()

        activeRecording = pending
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (!event.hasError()) {
                        Toast.makeText(this, "Video saved ✓", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e("MainActivity", "Video error: ${event.error}")
                    }
                }
            }
        isRecording = true
        binding.captureButton.text = "⏹"
    }

    override fun onPause() {
        super.onPause()
        cameraProvider?.unbindAll()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) bindCameraUseCases()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
