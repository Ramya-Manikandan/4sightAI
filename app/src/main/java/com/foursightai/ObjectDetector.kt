package com.foursightai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.scale
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boundingBoxArea: Float,
    val centerX: Float,
    val centerY: Float,
    val boundingBox: RectF
)

class ObjectDetector(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // YOLOv8n input: 640x640 RGB
    private val inputSize = 640
    private val numChannels = 3

    // YOLOv8n output: [1, 84, 8400]
    private val numDetections = 8400
    private val numClasses = 80

    private val confidenceThreshold = 0.30f // Slightly lowered for indoor objects
    private val iouThreshold = 0.45f

    val labels = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
        "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
        "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
        "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
        "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
        "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
        "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
        "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
        "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
        "toothbrush"
    )

    // Focus on indoor navigation & safety
    private val navigationRelevantLabels = setOf(
        "person", "chair", "couch", "dining table", "bed", "toilet",
        "potted plant", "tv", "laptop", "microwave", "oven", "sink", 
        "refrigerator", "book", "backpack", "suitcase", "bottle", "cup",
        "door", "stairs", "mouse", "remote", "keyboard", "cell phone"
    )

    private val detectionHitCounts = mutableMapOf<String, Int>()
    private val detectionMissCounts = mutableMapOf<String, Int>()
    private val activeDetections = mutableMapOf<String, DetectionResult>()
    private val hitThreshold = 2
    private val missThreshold = 4

    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(
        1 * inputSize * inputSize * numChannels * 4
    ).apply { order(ByteOrder.nativeOrder()) }

    private val outputArray = Array(1) { Array(84) { FloatArray(numDetections) } }
    private val pixelValues = IntArray(inputSize * inputSize)

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val assetFd = context.assets.openFd("yolov8n.tflite")
            val inputStream = FileInputStream(assetFd.fileDescriptor)
            val mappedBuffer = inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                assetFd.startOffset,
                assetFd.declaredLength
            )

            val options = Interpreter.Options()
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                try {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate!!)
                } catch (e: Throwable) {
                    gpuDelegate = null
                }
            }

            if (gpuDelegate == null) options.numThreads = 4
            interpreter = Interpreter(mappedBuffer, options)
            Log.i("ObjectDetector", "YOLOv8n indoor-ready model loaded")

        } catch (e: Exception) {
            Log.e("ObjectDetector", "Model load failed: ${e.message}")
            interpreter = null
        }
    }

    fun analyze(bitmap: Bitmap): List<DetectionResult> {
        if (interpreter == null) return emptyList()
        return applySmoothingFilter(runRealInference(bitmap))
    }

    private fun runRealInference(bitmap: Bitmap): List<DetectionResult> {
        val resized = bitmap.scale(inputSize, inputSize, true)
        resized.getPixels(pixelValues, 0, inputSize, 0, 0, inputSize, inputSize)

        inputBuffer.rewind()
        for (pixel in pixelValues) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        resized.recycle()

        inputBuffer.rewind()
        interpreter!!.run(inputBuffer, outputArray)

        val candidates = mutableListOf<DetectionResult>()
        for (i in 0 until numDetections) {
            val cx = outputArray[0][0][i]
            val cy = outputArray[0][1][i]
            val w  = outputArray[0][2][i]
            val h  = outputArray[0][3][i]

            var bestScore = 0f
            var bestClass = 0
            for (c in 0 until numClasses) {
                val score = outputArray[0][4 + c][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }

            if (bestScore < confidenceThreshold) continue

            val label = if (bestClass < labels.size) labels[bestClass] else "obstacle"
            
            // Prioritize indoor objects by slightly boosting their confidence
            val effectiveScore = if (label in navigationRelevantLabels) bestScore + 0.1f else bestScore

            candidates.add(
                DetectionResult(
                    label = label,
                    confidence = effectiveScore,
                    boundingBoxArea = w * h,
                    centerX = cx.coerceIn(0f, 1f),
                    centerY = cy.coerceIn(0f, 1f),
                    boundingBox = RectF(
                        (cx - w / 2f).coerceIn(0f, 1f),
                        (cy - h / 2f).coerceIn(0f, 1f),
                        (cx + w / 2f).coerceIn(0f, 1f),
                        (cy + h / 2f).coerceIn(0f, 1f)
                    )
                )
            )
        }

        return nonMaxSuppression(candidates)
    }

    private fun nonMaxSuppression(candidates: List<DetectionResult>): List<DetectionResult> {
        val sorted = candidates.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(it.boundingBox, best.boundingBox) > iouThreshold }
        }

        return kept
            .sortedWith(compareByDescending<DetectionResult> {
                if (it.label in navigationRelevantLabels) 2 else 0 
            }.thenByDescending { it.confidence })
            .take(5)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val interLeft   = maxOf(a.left,   b.left)
        val interTop    = maxOf(a.top,    b.top)
        val interRight  = minOf(a.right,  b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val unionArea = a.width() * a.height() + b.width() * b.height() - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    private fun applySmoothingFilter(rawResults: List<DetectionResult>): List<DetectionResult> {
        val currentLabels = rawResults.map { it.label }.toSet()
        for (detection in rawResults) {
            val label = detection.label
            detectionHitCounts[label] = (detectionHitCounts[label] ?: 0) + 1
            detectionMissCounts[label] = 0
            if ((detectionHitCounts[label] ?: 0) >= hitThreshold) activeDetections[label] = detection
        }

        val toRemove = mutableListOf<String>()
        for (label in activeDetections.keys) {
            if (label !in currentLabels) {
                detectionMissCounts[label] = (detectionMissCounts[label] ?: 0) + 1
                if ((detectionMissCounts[label] ?: 0) >= missThreshold) toRemove.add(label)
            }
        }
        toRemove.forEach {
            activeDetections.remove(it)
            detectionHitCounts.remove(it)
            detectionMissCounts.remove(it)
        }
        return activeDetections.values.toList()
    }

    fun isUsingRealModel(): Boolean = interpreter != null
    fun close() { interpreter?.close(); gpuDelegate?.close() }
}
