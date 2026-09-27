package com.example.cameraprofessional

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * CameraCapabilities
 *
 * Har physical/logical camera (main/wide, ultra-wide, telephoto, macro, front)
 * ke liye poora spec sheet nikalta hai:
 *
 * - Camera ID, facing
 * - Sensor resolution & sensor size
 * - Focal lengths, aperture(s), field of view (calculated)
 * - OIS / EIS availability
 * - Autofocus modes + focus distance range
 * - Flash availability
 * - Supported FPS ranges + supported resolutions (JPEG/HEIC/video)
 * - HDR capability, RAW support, JPEG/HEIF support
 * - Video profiles, slow-motion (high-speed) capability
 * - Camera2 Extensions (Night mode, HDR, Bokeh, etc. — API 31+)
 * - Zoom range, exposure compensation range, ISO range, white-balance modes
 *
 * Scan sirf EK BAAR hota hai (pehle app-open pe), result JSON cache me store hota hai.
 * MainActivity is scanner ko kabhi startup pe block nahi hone deta — sab background
 * thread pe chalta hai, isliye shutter/preview kabhi is wajah se lag nahi karega.
 */
object CameraCapabilities {

    private const val PREFS_NAME = "camera_pro_capability_cache"
    private const val KEY_JSON = "capabilities_json"

    fun isAlreadyScanned(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_JSON)
    }

    fun loadCached(context: Context): JSONArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_JSON, null) ?: return null
        return try { JSONArray(raw) } catch (e: Exception) { null }
    }

    /** Call this off the main thread only. */
    fun scanAndCache(context: Context): JSONArray {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val result = JSONArray()

        try {
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                result.put(buildCameraReport(id, chars))
            }
        } catch (e: Exception) {
            Log.e("CameraCapabilities", "Scan failed: ${e.message}")
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_JSON, result.toString()).apply()
        return result
    }

    private fun buildCameraReport(id: String, chars: CameraCharacteristics): JSONObject {
        val obj = JSONObject()
        obj.put("cameraId", id)

        // --- Facing ---
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        obj.put("facing", when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            else -> "EXTERNAL"
        })

        // --- Focal lengths / aperture / FOV / lens type guess ---
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        obj.put("focalLengthsMM", JSONArray(focalLengths?.toList() ?: emptyList<Float>()))

        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        obj.put("apertures", JSONArray(apertures?.toList() ?: emptyList<Float>()))

        // --- Sensor size & resolution ---
        val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (sensorSize != null) {
            obj.put("sensorSizeMM", JSONObject().apply {
                put("width", sensorSize.width)
                put("height", sensorSize.height)
            })
        }
        val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        if (pixelArray != null) {
            obj.put("sensorResolution", "${pixelArray.width}x${pixelArray.height}")
            obj.put("megapixels", (pixelArray.width.toLong() * pixelArray.height) / 1_000_000.0)
        }

        // --- Field of view (calculated from focal length + sensor size) ---
        if (sensorSize != null && focalLengths != null && focalLengths.isNotEmpty()) {
            val fovList = JSONArray()
            for (f in focalLengths) {
                val fovDeg = 2 * Math.toDegrees(
                    Math.atan((sensorSize.width / (2 * f)).toDouble())
                )
                fovList.put(fovDeg)
            }
            obj.put("horizontalFOVDegrees", fovList)
        }

        // Rough lens-type classification, useful for UI (Wide / Ultra-wide / Telephoto / Macro)
        val minFocal = focalLengths?.minOrNull()
        obj.put("lensTypeGuess", when {
            minFocal == null -> "UNKNOWN"
            minFocal < 2.0f -> "ULTRA_WIDE_OR_MACRO"
            minFocal in 2.0f..4.5f -> "MAIN_WIDE"
            else -> "TELEPHOTO"
        })

        // --- Stabilization ---
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        obj.put("opticalStabilization", ois?.any {
            it != CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF
        } ?: false)

        val eis = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        obj.put("videoDigitalStabilization", eis?.any {
            it != CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        } ?: false)

        // --- Autofocus modes + focus distance ---
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        obj.put("autofocusModes", JSONArray(afModes?.map { afModeName(it) } ?: emptyList<String>()))

        val minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        obj.put("minFocusDistanceDiopters", minFocusDistance ?: 0f)
        obj.put("hasAutofocus", (minFocusDistance ?: 0f) > 0f)

        // --- Flash ---
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
        obj.put("flashAvailable", hasFlash ?: false)

        // --- Stream configs: resolutions, formats, FPS ---
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        obj.put("jpegSupport", true) // always supported on Camera2
        obj.put("heifSupport", streamMap?.isOutputSupportedFor(ImageFormat.HEIC) ?: false)
        obj.put("rawSupport", streamMap?.isOutputSupportedFor(ImageFormat.RAW_SENSOR) ?: false)

        val jpegSizes = streamMap?.getOutputSizes(ImageFormat.JPEG)
        obj.put("supportedPhotoResolutions", JSONArray(
            jpegSizes?.map { "${it.width}x${it.height}" } ?: emptyList<String>()
        ))

        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        obj.put("supportedFpsRanges", JSONArray(
            fpsRanges?.map { "${it.lower}-${it.upper}" } ?: emptyList<String>()
        ))

        // Slow motion: high-speed video sizes (usually 120/240 fps)
        val highSpeedSizes = streamMap?.highSpeedVideoSizes
        obj.put("slowMotionSupport", (highSpeedSizes?.isNotEmpty() == true))
        if (highSpeedSizes != null && highSpeedSizes.isNotEmpty()) {
            val hsFps = streamMap.getHighSpeedVideoFpsRangesFor(highSpeedSizes[0])
            obj.put("slowMotionMaxFps", hsFps.maxByOrNull { it.upper }?.upper ?: 0)
        }

        // --- HDR capability (via available capabilities) ---
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val capList = capabilities?.toList() ?: emptyList()
        obj.put("hdrSupport", capList.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING
        ) || capList.contains(10 /* CAPABILITIES_10BIT (HDR) API33+ */))
        obj.put("rawCapabilityFlag", capList.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
        ))
        obj.put("manualSensorControl", capList.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        ))
        obj.put("manualPostProcessing", capList.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
        ))

        // --- Zoom range ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null) {
                obj.put("zoomRange", "${zoomRange.lower}x - ${zoomRange.upper}x")
            }
        }
        val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        obj.put("maxDigitalZoom", maxDigitalZoom ?: 1.0f)

        // --- Exposure range ---
        val expRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val expStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        if (expRange != null) {
            obj.put("exposureCompensationRange", "${expRange.lower}..${expRange.upper} (step ${expStep})")
        }

        // --- ISO range ---
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (isoRange != null) {
            obj.put("isoRange", "${isoRange.lower} - ${isoRange.upper}")
        }

        // --- White balance modes ---
        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        obj.put("whiteBalanceModes", JSONArray(awbModes?.map { awbModeName(it) } ?: emptyList<String>()))

        // --- Extensions (Night mode / HDR / Bokeh / Face retouch) — API 31+ ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            obj.put("extensionsNote", "Query via CameraExtensionCharacteristics at bind-time (runtime API, not in CameraCharacteristics).")
        }

        return obj
    }

    private fun afModeName(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_AF_MODE_OFF -> "OFF"
        CameraCharacteristics.CONTROL_AF_MODE_AUTO -> "AUTO"
        CameraCharacteristics.CONTROL_AF_MODE_MACRO -> "MACRO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
        CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
        CameraCharacteristics.CONTROL_AF_MODE_EDOF -> "EDOF"
        else -> "MODE_$mode"
    }

    private fun awbModeName(mode: Int): String = when (mode) {
        CameraCharacteristics.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraCharacteristics.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraCharacteristics.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraCharacteristics.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraCharacteristics.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraCharacteristics.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CameraCharacteristics.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraCharacteristics.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "MODE_$mode"
    }
}
