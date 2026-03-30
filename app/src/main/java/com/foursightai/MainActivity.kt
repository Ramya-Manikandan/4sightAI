package com.foursightai

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var voiceManager: VoiceManager
    private val PERMISSION_REQUEST_CODE = 101

    private val REQUIRED_PERMISSIONS = arrayOf(
        android.Manifest.permission.CAMERA,
        android.Manifest.permission.RECORD_AUDIO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkAndRequestPermissions()

        voiceManager = VoiceManager(this) { command ->
            if ("start" in command || "navigation" in command || "begin" in command) {
                launchCameraActivity()
            }
        }

        findViewById<Button>(R.id.btnLaunch).setOnClickListener {
            launchCameraActivity()
        }
    }

    override fun onResume() {
        super.onResume()
        voiceManager.speak("Welcome to 4Sight AI. Tap the button or say Start Navigation to begin.")
        voiceManager.startListening()
    }

    override fun onPause() {
        super.onPause()
        voiceManager.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.shutdown()
    }

    private fun launchCameraActivity() {
        voiceManager.stopListening()
        val intent = Intent(this, CameraActivity::class.java)
        startActivity(intent)
    }

    private fun checkAndRequestPermissions() {
        val needed = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                voiceManager.speak("Camera and microphone permissions are required for navigation.")
            }
        }
    }
}
