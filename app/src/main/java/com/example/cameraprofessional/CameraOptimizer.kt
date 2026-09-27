package com.example.cameraprofessional

import android.content.Context
import androidx.camera.video.Quality
import org.json.JSONArray
import org.json.JSONObject

/**
 * CameraOptimizer
 *
 * "⚡ OPTIMIZE CAMERA" ka core logic. Ye scanned CameraCapabilities data leke
 * device ke liye BEST configuration decide karta hai — aur usko cache kar deta
 * hai, taaki MainActivity/ProCameraActivity/etc har baar dobara decide na karein.
 *
 * Decide hota hai:
 * - Kaunsi photo resolution use karni hai (sabse bada supported jo lag na kare —
 *   hum "second highest" resolution choose karte hain taaki sabse extreme/slow
 *   sensor mode avoid ho, jab tak device flagship-tier na ho)
 * - Video quality tier (UHD/FHD/HD) — jo device support karta hai
 * - Stabilization on/off (agar OIS ya EIS available hai)
 * - AF mode (continuous picture, ya agar macro lens hai to macro bhi)
 * - HDR on/off (agar device HDR capability flag karta hai)
 * - Capture mode: MINIMIZE_LATENCY hamesha default (fast shutter priority)
 *
 * Ye sab ek chhoti si JSON profile me cache hota hai, jise dusri screens seedha
 * padh leti hain — koi dobara hardware scan nahi hota.
 */
data class OptimizedProfile(
    val cameraCount: Int,
    val rearLensCount: Int,
    val frontLensCount: Int,
    val has4K: Boolean,
    val hasHdr: Boolean,
    val hasStabilization: Boolean,
    val hasRaw: Boolean,
    val hasMacroLens: Boolean,
    val hasTelephotoLens: Boolean,
    val recommendedPhotoResolution: String,
    val recommendedVideoQuality: String, // "UHD" / "FHD" / "HD" / "SD"
    val recommendedFps: Int,
    val useStabilization: Boolean,
    val useHdr: Boolean,
    val afMode: String
)

object CameraOptimizer {

    private const val PREFS_NAME = "camera_pro_optimized_profile"
    private const val KEY_PROFILE = "profile_json"

    fun isOptimized(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_PROFILE)
    }

    fun loadCachedProfile(context: Context): OptimizedProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILE, null) ?: return null
        return try {
            val j = JSONObject(raw)
            OptimizedProfile(
                cameraCount = j.getInt("cameraCount"),
                rearLensCount = j.getInt("rearLensCount"),
                frontLensCount = j.getInt("frontLensCount"),
                has4K = j.getBoolean("has4K"),
                hasHdr = j.getBoolean("hasHdr"),
                hasStabilization = j.getBoolean("hasStabilization"),
                hasRaw = j.getBoolean("hasRaw"),
                hasMacroLens = j.getBoolean("hasMacroLens"),
                hasTelephotoLens = j.getBoolean("hasTelephotoLens"),
                recommendedPhotoResolution = j.getString("recommendedPhotoResolution"),
                recommendedVideoQuality = j.getString("recommendedVideoQuality"),
                recommendedFps = j.getInt("recommendedFps"),
                useStabilization = j.getBoolean("useStabilization"),
                useHdr = j.getBoolean("useHdr"),
                afMode = j.getString("afMode")
            )
        } catch (e: Exception) { null }
    }

    /** Runs off the main thread. Reads (or triggers) the capability scan, then decides + caches. */
    fun runOptimization(context: Context): OptimizedProfile {
        val caps = CameraCapabilities.loadCached(context)
            ?: CameraCapabilities.scanAndCache(context)

        var rearCount = 0
        var frontCount = 0
        var has4K = false
        var hasHdr = false
        var hasStabilization = false
        var hasRaw = false
        var hasMacro = false
        var hasTele = false
        var bestResolution = "1920x1080"
        var bestPixelCount = 0L
        var bestFps = 30

        for (i in 0 until caps.length()) {
            val cam = caps.getJSONObject(i)
            if (cam.optString("facing") == "FRONT") frontCount++ else rearCount++
            if (cam.optBoolean("hdrSupport")) hasHdr = true
            if (cam.optBoolean("opticalStabilization") || cam.optBoolean("videoDigitalStabilization")) {
                hasStabilization = true
            }
            if (cam.optBoolean("rawSupport")) hasRaw = true
            when (cam.optString("lensTypeGuess")) {
                "ULTRA_WIDE_OR_MACRO" -> hasMacro = true
                "TELEPHOTO" -> hasTele = true
            }

            // Pick second-highest resolution (safer + faster than the absolute max on
            // mid-range sensors, avoids the slowest capture mode while staying sharp).
            val resList: JSONArray? = cam.optJSONArray("supportedPhotoResolutions")
            if (resList != null) {
                val parsed = (0 until resList.length()).mapNotNull { idx ->
                    val s = resList.optString(idx)
                    val parts = s.split("x")
                    if (parts.size == 2) {
                        val w = parts[0].toLongOrNull(); val h = parts[1].toLongOrNull()
                        if (w != null && h != null) Triple(s, w, h) else null
                    } else null
                }.sortedByDescending { it.second * it.third }

                if (parsed.isNotEmpty()) {
                    val pick = if (parsed.size > 1) parsed[1] else parsed[0]
                    if (pick.second * pick.third > bestPixelCount) {
                        bestPixelCount = pick.second * pick.third
                        bestResolution = pick.first
                    }
                }
                // 4K video proxy: if sensor can output ~3840x2160 or larger
                if (parsed.any { it.second >= 3840 && it.third >= 2160 }) has4K = true
            }

            val fpsRanges = cam.optJSONArray("supportedFpsRanges")
            if (fpsRanges != null) {
                for (idx in 0 until fpsRanges.length()) {
                    val range = fpsRanges.optString(idx) // "lower-upper"
                    val upper = range.substringAfter("-").toIntOrNull() ?: 0
                    if (upper in 31..60 && upper > bestFps) bestFps = 60
                    else if (upper == 30 && bestFps < 30) bestFps = 30
                }
            }
        }

        val videoQuality = when {
            has4K -> "UHD"
            bestPixelCount >= 1920L * 1080L -> "FHD"
            else -> "HD"
        }

        val profile = OptimizedProfile(
            cameraCount = caps.length(),
            rearLensCount = rearCount,
            frontLensCount = frontCount,
            has4K = has4K,
            hasHdr = hasHdr,
            hasStabilization = hasStabilization,
            hasRaw = hasRaw,
            hasMacroLens = hasMacro,
            hasTelephotoLens = hasTele,
            recommendedPhotoResolution = bestResolution,
            recommendedVideoQuality = videoQuality,
            recommendedFps = bestFps,
            useStabilization = hasStabilization,
            useHdr = hasHdr,
            afMode = "CONTINUOUS_PICTURE"
        )

        cacheProfile(context, profile)
        return profile
    }

    private fun cacheProfile(context: Context, p: OptimizedProfile) {
        val j = JSONObject().apply {
            put("cameraCount", p.cameraCount)
            put("rearLensCount", p.rearLensCount)
            put("frontLensCount", p.frontLensCount)
            put("has4K", p.has4K)
            put("hasHdr", p.hasHdr)
            put("hasStabilization", p.hasStabilization)
            put("hasRaw", p.hasRaw)
            put("hasMacroLens", p.hasMacroLens)
            put("hasTelephotoLens", p.hasTelephotoLens)
            put("recommendedPhotoResolution", p.recommendedPhotoResolution)
            put("recommendedVideoQuality", p.recommendedVideoQuality)
            put("recommendedFps", p.recommendedFps)
            put("useStabilization", p.useStabilization)
            put("useHdr", p.useHdr)
            put("afMode", p.afMode)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROFILE, j.toString()).apply()
    }

    fun videoQualityToCameraXQuality(tier: String): Quality = when (tier) {
        "UHD" -> Quality.UHD
        "FHD" -> Quality.FHD
        "HD" -> Quality.HD
        else -> Quality.SD
    }
}
