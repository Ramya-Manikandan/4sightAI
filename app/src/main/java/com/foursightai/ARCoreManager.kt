package com.foursightai

import android.content.Context
import android.graphics.RectF
import android.util.Log
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException

/**
 * ARCoreManager — AR Optional implementation.
 *
 * Behaviour:
 *   - On devices WITH ARCore support     → creates a real ARCore session,
 *     enables the Depth API, and returns accurate per-pixel depth in metres.
 *   - On devices WITHOUT ARCore support  → isAvailable = false, all depth
 *     queries fall back to bounding-box heuristic estimation.
 *
 * The rest of the app never needs to branch on this; it always calls
 * getDistanceMetres() and gets a sensible answer either way.
 */
class ARCoreManager(private val context: Context) {

    var isAvailable: Boolean = false
        private set

    var isDepthSupported: Boolean = false
        private set

    private var session: Session? = null
    private var currentFrame: Frame? = null

    // ─── Initialisation ───────────────────────────────────────────────────────

    /**
     * Call once from CameraActivity.onCreate().
     * Returns a human-readable status string for the UI status bar.
     */
    fun initialise(): String {
        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(context)

            when {
                availability.isSupported -> {
                    session = Session(context).also { s ->
                        val config = Config(s).apply {
                            // Enable Depth API if the device supports it
                            depthMode = if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                                isDepthSupported = true
                                Config.DepthMode.AUTOMATIC
                            } else {
                                Config.DepthMode.DISABLED
                            }
                            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            focusMode = Config.FocusMode.AUTO
                        }
                        s.configure(config)
                    }
                    isAvailable = true
                    Log.i("ARCoreManager", "ARCore ready. Depth supported: $isDepthSupported")
                    if (isDepthSupported) "AR + Depth Active" else "AR Active"
                }

                availability == ArCoreApk.Availability.UNKNOWN_CHECKING -> {
                    // ARCore may be available after Play Store install — treat as unavailable for now
                    isAvailable = false
                    "AR: Checking..."
                }

                else -> {
                    isAvailable = false
                    Log.w("ARCoreManager", "ARCore not supported on this device — fallback mode")
                    "Fallback Mode"
                }
            }
        } catch (e: UnavailableException) {
            isAvailable = false
            Log.e("ARCoreManager", "ARCore unavailable: ${e.message}")
            "Fallback Mode"
        } catch (e: Exception) {
            isAvailable = false
            Log.e("ARCoreManager", "ARCore init error: ${e.message}")
            "Fallback Mode"
        }
    }

    // ─── Frame update (call every camera frame) ───────────────────────────────

    fun onCameraFrame() {
        if (!isAvailable) return
        try {
            session?.let { s ->
                currentFrame = s.update()
                if (currentFrame?.camera?.trackingState != TrackingState.TRACKING) {
                    currentFrame = null
                }
            }
        } catch (e: Exception) {
            currentFrame = null
        }
    }

    // ─── Depth query ──────────────────────────────────────────────────────────

    /**
     * Returns the real-world distance in metres to the object at the centre
     * of the given normalised bounding box [0..1, 0..1].
     *
     * If ARCore + Depth API is active → samples the depth image at the box centre.
     * If not available → falls back to the bounding-box-area heuristic.
     *
     * @param box          Normalised bounding box (values 0–1)
     * @param boundingBoxArea  Fraction of screen area occupied by the box
     * @return distance in metres (clamped 0.2–10 m)
     */
    fun getDistanceMetres(box: RectF, boundingBoxArea: Float): Float {
        if (isAvailable && isDepthSupported) {
            val arDepth = queryARCoreDepth(box)
            if (arDepth != null) return arDepth
        }
        // Fallback: bounding-box heuristic (calibrated constant 0.5)
        return fallbackDistance(boundingBoxArea)
    }

    private fun queryARCoreDepth(box: RectF): Float? {
        val frame = currentFrame ?: return null
        return try {
            frame.acquireDepthImage16Bits().use { depthImage ->
                val imgW = depthImage.width.toFloat()
                val imgH = depthImage.height.toFloat()

                // Sample at box centre
                val px = ((box.left + box.right) / 2f * imgW).toInt().coerceIn(0, depthImage.width - 1)
                val py = ((box.top + box.bottom) / 2f * imgH).toInt().coerceIn(0, depthImage.height - 1)

                // Depth image is 16-bit unsigned, values in millimetres
                val plane = depthImage.planes[0]
                val byteIndex = py * plane.rowStride + px * plane.pixelStride
                val depthMm = plane.buffer.getShort(byteIndex).toInt() and 0xFFFF

                if (depthMm == 0) null  // 0 = no depth data at this pixel
                else (depthMm / 1000f).coerceIn(0.2f, 10f)
            }
        } catch (e: NotYetAvailableException) {
            null
        } catch (e: Exception) {
            Log.w("ARCoreManager", "Depth query failed: ${e.message}")
            null
        }
    }

    private fun fallbackDistance(boundingBoxArea: Float): Float {
        if (boundingBoxArea <= 0.005f) return 10f
        return (0.5f / boundingBoxArea).coerceIn(0.2f, 10f)
    }

    // ─── Human-friendly distance strings ─────────────────────────────────────

    fun getHumanFriendlyDistance(distanceMetres: Float): String = when {
        distanceMetres <= 0.5f -> "very close"
        distanceMetres <= 1.2f -> "one metre ahead"
        distanceMetres <= 2.2f -> "two metres ahead"
        distanceMetres <= 3.2f -> "three metres ahead"
        distanceMetres <= 4.5f -> "four metres ahead"
        distanceMetres <= 6f   -> "five metres ahead"
        else                   -> "far ahead"
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun resume() {
        try { session?.resume() } catch (e: Exception) { Log.e("ARCoreManager", "resume: ${e.message}") }
    }

    fun pause() {
        try { session?.pause() } catch (e: Exception) { Log.e("ARCoreManager", "pause: ${e.message}") }
    }

    fun close() {
        try { session?.close() } catch (e: Exception) { Log.e("ARCoreManager", "close: ${e.message}") }
        session = null
    }
}