package com.foursightai

import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

enum class CommandState { IDLE, INSTRUCTION_GIVEN, WAITING_FOR_USER_MOVEMENT }

data class NavigationInstruction(
    val text: String,
    val urgency: HapticManager.Urgency
)

class NavigationEngine {

    private val STEP_LENGTH_M      = 0.75f
    private val MOVEMENT_THRESHOLD_X    = 0.12f
    private val MOVEMENT_THRESHOLD_DIST = 0.4f

    // Objects within this distance of screen edges are treated as side hazards
    // Updated to 33% / 66% as requested
    private val CENTER_BAND_LEFT  = 0.33f
    private val CENTER_BAND_RIGHT = 0.66f

    private var currentState       = CommandState.IDLE
    private var lastInstructedX    = -1f
    private var lastInstructedDist = -1f
    private var lastGuidanceTime   = 0L
    private var hasRepeated        = false
    private var lastInstruction: NavigationInstruction? = null

    var isInstructionActive = false
        private set

    // ─── Main entry point — called every camera frame ─────────────────────────

    fun processEnvironment(
        detections: List<DetectionResult>,
        arCore: ARCoreManager
    ): NavigationInstruction? {

        val analysed = detections.map { det ->
            det to arCore.getDistanceMetres(det.boundingBox, det.boundingBoxArea)
        }

        val closeObstacles  = analysed.filter { it.second <= 2.0f }
        val centerObstacles = analysed.filter {
            it.first.centerX in CENTER_BAND_LEFT..CENTER_BAND_RIGHT
        }

        val hasCloseObstacle  = closeObstacles.isNotEmpty()
        val hasCenterObstacle = centerObstacles.isNotEmpty()

        if (!hasCloseObstacle && !hasCenterObstacle) {
            if (!isInstructionActive) {
                if (currentState == CommandState.WAITING_FOR_USER_MOVEMENT ||
                    currentState == CommandState.INSTRUCTION_GIVEN) {
                    resetState()
                    return NavigationInstruction(
                        "Path clear. Continue forward.",
                        HapticManager.Urgency.CLEAR
                    )
                }
            }
        }

        val closest = analysed.filter { it.second <= 6f }.minByOrNull { it.second }

        if (isInstructionActive && lastInstructedDist != -1f) {
            val now = System.currentTimeMillis()
            var didMove = false

            if (closest != null) {
                val distShift  = abs(closest.second - lastInstructedDist)
                val centerShift = abs(closest.first.centerX - lastInstructedX)
                if (distShift >= MOVEMENT_THRESHOLD_DIST || centerShift >= MOVEMENT_THRESHOLD_X) {
                    didMove = true
                }
            } else if (!hasCloseObstacle && !hasCenterObstacle) {
                didMove = true
            }

            if (didMove) {
                resetState()
            } else {
                currentState = CommandState.WAITING_FOR_USER_MOVEMENT
                if (!hasRepeated && now - lastGuidanceTime >= 4000L) {
                    hasRepeated = true
                    lastGuidanceTime = now
                    return lastInstruction
                }
                return null
            }
        }

        if (closest == null) return null

        val (closestDet, closestDist) = closest
        val centerX = closestDet.centerX

        // Logic based on bounding box center relative to screen width
        val objectDirection = when {
            centerX < CENTER_BAND_LEFT  -> "left"
            centerX > CENTER_BAND_RIGHT -> "right"
            else                        -> "center"
        }

        // Fix logic: if box is left, user should move RIGHT to avoid it.
        val safeDirection = when (objectDirection) {
            "left"   -> "right"
            "right"  -> "left"
            else     -> chooseSafeDirection(analysed, "center")
        }

        val name = closestDet.label.replaceFirstChar { it.uppercase() }
        val distText = arCore.getHumanFriendlyDistance(closestDist)
        val steps = (closestDist / STEP_LENGTH_M).roundToInt().coerceAtLeast(1)
        val stepText = if (steps == 1) "one step" else "$steps steps"

        val (text, urgency) = when {
            closestDist <= 0.8f && objectDirection == "center" -> Pair(
                "$name ahead! STOP and move $safeDirection.",
                HapticManager.Urgency.STOP
            )
            closestDist <= 1.5f -> Pair(
                "$name close $distText. Move $safeDirection now.",
                HapticManager.Urgency.URGENT
            )
            else -> Pair(
                "$name detected $distText. Move $safeDirection.",
                HapticManager.Urgency.WARNING
            )
        }

        Log.d("NavigationEngine", "Obstacle: ${closestDet.label} at x=${"%.2f".format(centerX)} ($objectDirection) -> Move $safeDirection")

        lastInstructedDist = closestDist
        lastInstructedX    = centerX
        lastGuidanceTime   = System.currentTimeMillis()
        currentState       = CommandState.INSTRUCTION_GIVEN
        isInstructionActive = true
        hasRepeated        = false

        val instruction = NavigationInstruction(text, urgency)
        lastInstruction  = instruction
        return instruction
    }

    private fun chooseSafeDirection(
        analysed: List<Pair<DetectionResult, Float>>,
        obstacleDirection: String
    ): String {
        val naturalSafe = when (obstacleDirection) {
            "left"   -> "right"
            "right"  -> "left"
            else     -> "left"
        }

        val naturalSideBlocked = analysed.any { (det, dist) ->
            dist <= 2f && when (naturalSafe) {
                "right" -> det.centerX > CENTER_BAND_RIGHT
                else    -> det.centerX < CENTER_BAND_LEFT
            }
        }

        return if (naturalSideBlocked) {
            if (naturalSafe == "left") "right" else "left"
        } else {
            naturalSafe
        }
    }

    fun describeSurroundings(detections: List<DetectionResult>, arCore: ARCoreManager): String {
        if (detections.isEmpty()) return "No obstacles detected. Path appears clear."

        val analysed = detections
            .map { it to arCore.getDistanceMetres(it.boundingBox, it.boundingBoxArea) }
            .sortedBy { it.second }

        return buildString {
            append("${detections.size} object${if (detections.size > 1) "s" else ""} detected. ")
            analysed.forEachIndexed { i, (det, dist) ->
                val dir = when {
                    det.centerX < CENTER_BAND_LEFT  -> "on your left"
                    det.centerX > CENTER_BAND_RIGHT -> "on your right"
                    else                            -> "ahead"
                }
                append("${det.label.replaceFirstChar { it.uppercase() }} $dir, ${arCore.getHumanFriendlyDistance(dist)}")
                if (i < analysed.size - 1) append(". ")
            }
        }
    }

    fun resetState() {
        currentState        = CommandState.IDLE
        isInstructionActive = false
        lastInstructedX     = -1f
        lastInstructedDist  = -1f
        hasRepeated         = false
        lastInstruction     = null
    }
}
