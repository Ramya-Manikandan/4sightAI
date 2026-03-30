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

    // Center band for obstacle avoidance
    private val CENTER_BAND_LEFT  = 0.30f
    private val CENTER_BAND_RIGHT = 0.70f

    private var currentState       = CommandState.IDLE
    private var lastInstructedX    = -1f
    private var lastInstructedDist = -1f
    private var lastGuidanceTime   = 0L
    private var lastClearTime      = 0L
    private var hasRepeated        = false
    private var lastInstruction: NavigationInstruction? = null
    
    private val CLEAR_PATH_REASSURANCE_INTERVAL = 12000L // Periodic "Path clear" every 12s

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

        val now = System.currentTimeMillis()

        // Handle Clear Path instructions (both state transitions and periodic reassurance)
        if (!hasCloseObstacle && !hasCenterObstacle) {
            val needsTransitionClear = (currentState == CommandState.WAITING_FOR_USER_MOVEMENT || 
                                       currentState == CommandState.INSTRUCTION_GIVEN)
            val needsPeriodicClear = (now - lastClearTime > CLEAR_PATH_REASSURANCE_INTERVAL)

            if (needsTransitionClear || (currentState == CommandState.IDLE && needsPeriodicClear)) {
                resetState()
                lastClearTime = now
                return NavigationInstruction(
                    "Path clear. Continue forward.",
                    HapticManager.Urgency.CLEAR
                )
            }
            // Keep silent if already clear and not yet time for reassurance
            return null
        }

        // Reset clear path timer when an obstacle is detected
        lastClearTime = now

        val closest = analysed.filter { it.second <= 6f }.minByOrNull { it.second }

        if (isInstructionActive && lastInstructedDist != -1f) {
            var didMove = false

            if (closest != null) {
                val distShift  = abs(closest.second - lastInstructedDist)
                val centerShift = abs(closest.first.centerX - lastInstructedX)
                if (distShift >= MOVEMENT_THRESHOLD_DIST || centerShift >= MOVEMENT_THRESHOLD_X) {
                    didMove = true
                }
            } else {
                // Obstacle gone
                didMove = true
            }

            if (didMove) {
                resetState()
            } else {
                currentState = CommandState.WAITING_FOR_USER_MOVEMENT
                if (!hasRepeated && now - lastGuidanceTime >= 4500L) {
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

        // Use balanced direction choice
        val safeDirection = chooseSafeDirection(analysed, closestDet)

        val name = closestDet.label.replaceFirstChar { it.uppercase() }
        val distText = arCore.getHumanFriendlyDistance(closestDist)

        val (text, urgency) = when {
            closestDist <= 0.8f && centerX in CENTER_BAND_LEFT..CENTER_BAND_RIGHT -> Pair(
                "$name directly ahead! STOP and move $safeDirection.",
                HapticManager.Urgency.STOP
            )
            closestDist <= 1.5f -> Pair(
                "$name very close $distText. Move $safeDirection now.",
                HapticManager.Urgency.URGENT
            )
            else -> Pair(
                "$name detected $distText. Move $safeDirection.",
                HapticManager.Urgency.WARNING
            )
        }

        Log.d("NavigationEngine", "Obstacle: ${closestDet.label} at x=${"%.2f".format(centerX)} -> Move $safeDirection")

        lastInstructedDist = closestDist
        lastInstructedX    = centerX
        lastGuidanceTime   = now
        currentState       = CommandState.INSTRUCTION_GIVEN
        isInstructionActive = true
        hasRepeated        = false

        val instruction = NavigationInstruction(text, urgency)
        lastInstruction  = instruction
        return instruction
    }

    private fun chooseSafeDirection(
        analysed: List<Pair<DetectionResult, Float>>,
        closestObstacle: DetectionResult
    ): String {
        val centerX = closestObstacle.centerX
        
        // If obstacle is clearly on one side, move to the other.
        if (centerX < CENTER_BAND_LEFT) return "right"
        if (centerX > CENTER_BAND_RIGHT) return "left"

        // Obstacle is in the center. Compare side clearances.
        val leftObstacles = analysed.filter { it.first.centerX < CENTER_BAND_LEFT }
        val rightObstacles = analysed.filter { it.first.centerX > CENTER_BAND_RIGHT }

        val minLeftDist = leftObstacles.minByOrNull { it.second }?.second ?: 10f
        val minRightDist = rightObstacles.minByOrNull { it.second }?.second ?: 10f

        return when {
            minRightDist > minLeftDist -> "right"
            minLeftDist > minRightDist -> "left"
            else -> {
                // If both sides are equally clear, move away from the object's slight bias
                if (centerX < 0.5f) "right" else "left"
            }
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
