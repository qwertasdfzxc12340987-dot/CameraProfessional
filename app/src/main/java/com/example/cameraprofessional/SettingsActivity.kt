package com.example.cameraprofessional

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.cameraprofessional.databinding.ActivitySettingsBinding

/**
 * SettingsActivity — as requested, Camera Diagnostics lives inside Settings,
 * not as a separate top-level mode. Also links to Specs and re-run Optimizer.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.diagnosticsRow.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        binding.capabilitiesRow.setOnClickListener {
            startActivity(Intent(this, CapabilitiesActivity::class.java))
        }
        binding.reoptimizeRow.setOnClickListener {
            startActivity(Intent(this, OptimizeActivity::class.java))
        }
        binding.clearCacheRow.setOnClickListener {
            getSharedPreferences("camera_pro_capability_cache", MODE_PRIVATE).edit().clear().apply()
            getSharedPreferences("camera_pro_optimized_profile", MODE_PRIVATE).edit().clear().apply()
            binding.clearCacheStatus.text = "Cache cleared. Re-scan will run on next optimize."
        }
    }
}
