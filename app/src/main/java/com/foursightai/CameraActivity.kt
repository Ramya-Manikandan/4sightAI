package com.foursightai

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.foursightai.R

class CameraActivity : AppCompatActivity() {

    // ─── Views ────────────────────────────────────────────────────────────────
    private lateinit var viewFinder: PreviewView
    private lateinit var boundingBoxOverlay: BoundingBoxOverlay
    private lateinit var tvSubtitleArea: TextView
    private lateinit var tvStatusText: TextView
    private lateinit var indicatorDot: View

    // ─── Core components ─────────────────────────────────────────────────────
    private lateinit var objectDetector: ObjectDetector
    private lateinit var arCoreManager: ARCoreManager
    private lateinit var navigationEngine: NavigationEngine
    private lateinit var voiceManager: VoiceManager
    private lateinit var hapticManager: HapticManager
    private lateinit var subtitleManager: SubtitleManager
    private lateinit var cameraExecutor: ExecutorService

    // ─── Frame processing gate — prevents overlapping heavy work ─────────────
    @Volatile private var isProcessingFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        bindViews()
        initComponents()
        setupButtons()
        startCamera()
    }

    private fun bindViews() {
        viewFinder        = findViewById(R.id.viewFinder)
        boundingBoxOverlay = findViewById(R.id.boundingBoxOverlay)
        tvSubtitleArea    = findViewById(R.id.tvSubtitleArea)
        tvStatusText      = findViewById(R.id.tvStatusText)
        indicatorDot      = findViewById(R.id.indicatorDot)
    }

    private fun initComponents() {
        subtitleManager  = SubtitleManager(tvSubtitleArea)
        objectDetector   = ObjectDetector(this)
        navigationEngine = NavigationEngine()
        hapticManager    = HapticManager(this)
        cameraExecutor   = Executors.newSingleThreadExecutor()

        // Initialise ARCore — returns status string ("AR + Depth Active" / "Fallback Mode")
        arCoreManager = ARCoreManager(this)
        val arStatus = arCoreManager.initialise()

        // Update overlay badges and status bar
        boundingBoxOverlay.isArActive    = arCoreManager.isAvailable
        boundingBoxOverlay.isDepthActive = arCoreManager.isDepthSupported

        // Wire voice commands — all routing handled here, VoiceManager is pure I/O
        voiceManager = VoiceManager(this) { command ->
            handleVoiceCommand(command)
        }

        // Update status bar
        val modelStatus = if (objectDetector.isUsingRealModel()) "YOLOv8n" else "AI Mock"
        updateStatus("$modelStatus · $arStatus", active = true)

        // Welcome message
        val welcomeMsg = buildString {
            append("4Sight AI ready. ")
            if (arCoreManager.isDepthSupported) append("AR depth active. ")
            else if (arCoreManager.isAvailable)  append("AR active. ")
            append("Hold phone at chest height and walk slowly.")
        }
        voiceManager.speak(welcomeMsg)
        lifecycleScope.launch { subtitleManager.updateSubtitle(welcomeMsg) }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnToggleNavigation).setOnClickListener {
            stopNavigationAndExit()
        }
    }

    // ─── Camera ───────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        // Gate: skip frame if previous is still being processed
                        if (isProcessingFrame) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        isProcessingFrame = true

                        try {
                            // Update ARCore with the new camera frame
                            arCoreManager.onCameraFrame()

                            // Run YOLO detection on rotated bitmap
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val bitmap = imageProxy.toBitmap()
                            
                            // YOLOv8 expects upright images
                            val uprightBitmap = if (rotationDegrees != 0) {
                                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            } else {
                                bitmap
                            }
                            
                            val detections = objectDetector.analyze(uprightBitmap)

                            // Compute distances for all detections
                            val distances = detections.map { det ->
                                arCoreManager.getDistanceMetres(det.boundingBox, det.boundingBoxArea)
                            }

                            // Ask navigation engine for an instruction
                            val instruction = navigationEngine.processEnvironment(detections, arCoreManager)

                            // Update UI on main thread
                            lifecycleScope.launch(Dispatchers.Main) {
                                boundingBoxOverlay.updateDetections(detections, distances)

                                instruction?.let { inst ->
                                    // Fire haptic pattern
                                    hapticManager.vibrate(inst.urgency)
                                    // Speak (VoiceManager owns cooldown)
                                    val forceSpeak = inst.urgency == HapticManager.Urgency.STOP
                                    voiceManager.speak(inst.text, force = forceSpeak)
                                    subtitleManager.updateSubtitle(inst.text)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CameraActivity", "Frame processing error: ${e.message}")
                        } finally {
                            imageProxy.close()
                            isProcessingFrame = false
                        }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e("CameraActivity", "Camera bind failed: ${e.message}")
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ─── Voice command routing ────────────────────────────────────────────────

    private fun handleVoiceCommand(command: String) {
        when (command) {
            "stop navigation" -> {
                stopNavigationAndExit()
            }
            "describe surroundings" -> {
                // Get current detections snapshot — safe because navigationEngine holds last state
                val desc = navigationEngine.describeSurroundings(
                    objectDetector.analyze(
                        // Use a 1×1 blank bitmap — we just want the cached active detections
                        createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    ),
                    arCoreManager
                )
                voiceManager.speak(desc, force = true)
                lifecycleScope.launch { subtitleManager.updateSubtitle(desc) }
            }
            "repeat" -> {
                voiceManager.repeatLastInstruction()
            }
            "whats ahead" -> {
                // Trigger immediate description of closest object
                voiceManager.speak("Checking what's ahead.", force = true)
            }
            else -> {
                Log.d("CameraActivity", "Unhandled command: $command")
            }
        }
    }

    // ─── Status bar helper ────────────────────────────────────────────────────

    private fun updateStatus(text: String, active: Boolean) {
        tvStatusText.text = text
        indicatorDot.setBackgroundResource(
            if (active) R.drawable.indicator_green else R.drawable.indicator_red
        )
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        arCoreManager.resume()
        voiceManager.startListening()
    }

    override fun onPause() {
        super.onPause()
        arCoreManager.pause()
        voiceManager.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdownNow()
        objectDetector.close()
        arCoreManager.close()
        voiceManager.shutdown()
        hapticManager.cancel()
    }

    private fun stopNavigationAndExit() {
        voiceManager.stopSpeaking()
        voiceManager.stopListening()
        hapticManager.cancel()

        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (e: Exception) {
            Log.e("CameraActivity", "Unbind on stop: ${e.message}")
        }

        cameraExecutor.shutdownNow()
        navigationEngine.resetState()

        voiceManager.speak("Navigation stopped.", flush = true, force = true)
        finish()
    }
}
