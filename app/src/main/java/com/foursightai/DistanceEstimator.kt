package com.foursightai

class DistanceEstimator {

    /**
     * Estimates distance to an object purely based on its bounding box area.
     * In a real implementation, this would use camera intrinsic parameters or ARCore.
     *
     * Rule: larger bounding box -> object closer.
     * smaller bounding box -> object farther.
     *
     * @param boundingBoxArea Percentage of screen taken by object [0.0 - 1.0].
     * @return Estimated distance in meters.
     */
    fun estimateDistance(boundingBoxArea: Float): Float {
        if (boundingBoxArea <= 0.01f) return 10.0f // Very far

        val distance = 0.5f / boundingBoxArea
        return Math.min(10f, Math.max(0.2f, distance)) // Clamp between 0.2m and 10m
    }

    /**
     * Converts a numeric distance in meters into a human-friendly string for TTS.
     */
    fun getHumanFriendlyDistance(distance: Float): String {
        return when {
            distance <= 0.5f -> "very close"
            distance <= 1.5f -> "one meter ahead"
            distance <= 2.5f -> "two meters ahead"
            distance <= 3.5f -> "three meters ahead"
            distance <= 4.5f -> "four meters ahead"
            distance <= 5.5f -> "five meters ahead"
            else -> "far ahead"
        }
    }
}
