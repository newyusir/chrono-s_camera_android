package com.yusir.chronoscamera.autoscroll
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.abs
object ScrollbarDetector {
    private const val TAG = "ScrollbarDetector"
    private const val SCAN_WIDTH_RATIO = 0.05f  
    private const val MIN_SCROLLBAR_HEIGHT_RATIO = 0.08f  
    private const val MAX_SCROLLBAR_HEIGHT_RATIO = 0.6f   
    private const val SCROLLBAR_GRAY_TOLERANCE = 50  
    private const val MIN_GRAY_VALUE = 80   
    private const val MAX_GRAY_VALUE = 200  
    private const val SCROLLBAR_WIDTH_MIN = 3   
    private const val SCROLLBAR_WIDTH_MAX = 30  
    data class ScrollbarInfo(
        val found: Boolean,
        val trackTop: Int,      
        val trackBottom: Int,   
        val thumbTop: Int,      
        val thumbBottom: Int,   
        val scrollbarX: Int,    
        val isAtBottom: Boolean 
    ) {
        companion object {
            val NOT_FOUND = ScrollbarInfo(
                found = false,
                trackTop = 0,
                trackBottom = 0,
                thumbTop = 0,
                thumbBottom = 0,
                scrollbarX = 0,
                isAtBottom = false
            )
        }
    }
    fun detectScrollbar(
        bitmap: Bitmap,
        topMargin: Int = 0,
        bottomMargin: Int = 0
    ): ScrollbarInfo {
        val width = bitmap.width
        val height = bitmap.height
        val scanStartX = (width * (1 - SCAN_WIDTH_RATIO)).toInt()
        val scanTop = topMargin
        val scanBottom = height - bottomMargin
        if (scanBottom <= scanTop) {
            Log.w(TAG, "Invalid scan area: top=$scanTop, bottom=$scanBottom")
            return ScrollbarInfo.NOT_FOUND
        }
        var bestScrollbarX = -1
        var bestScrollbarScore = 0
        var bestThumbTop = -1
        var bestThumbBottom = -1
        for (x in scanStartX until width - 2) {
            val result = analyzeVerticalLine(bitmap, x, scanTop, scanBottom)
            if (result.score > bestScrollbarScore && result.thumbTop >= 0) {
                bestScrollbarScore = result.score
                bestScrollbarX = x
                bestThumbTop = result.thumbTop
                bestThumbBottom = result.thumbBottom
            }
        }
        if (bestScrollbarX < 0 || bestThumbTop < 0) {
            Log.d(TAG, "No scrollbar found")
            return ScrollbarInfo.NOT_FOUND
        }
        val trackTop = scanTop
        val trackBottom = scanBottom
        val thumbHeight = bestThumbBottom - bestThumbTop
        val trackHeight = trackBottom - trackTop
        val bottomThreshold = maxOf((trackHeight * 0.05).toInt(), 50)
        val distanceFromBottom = trackBottom - bestThumbBottom
        val isAtBottom = distanceFromBottom <= bottomThreshold
        Log.d(TAG, "Bottom check: distanceFromBottom=$distanceFromBottom, threshold=$bottomThreshold")
        Log.d(TAG, "Scrollbar found: x=$bestScrollbarX, thumbTop=$bestThumbTop, thumbBottom=$bestThumbBottom, isAtBottom=$isAtBottom")
        return ScrollbarInfo(
            found = true,
            trackTop = trackTop,
            trackBottom = trackBottom,
            thumbTop = bestThumbTop,
            thumbBottom = bestThumbBottom,
            scrollbarX = bestScrollbarX,
            isAtBottom = isAtBottom
        )
    }
    private data class LineAnalysisResult(
        val score: Int,
        val thumbTop: Int,
        val thumbBottom: Int
    )
    private fun analyzeVerticalLine(
        bitmap: Bitmap,
        x: Int,
        scanTop: Int,
        scanBottom: Int
    ): LineAnalysisResult {
        val grayPixels = mutableListOf<Int>()
        var currentRunStart = -1
        var currentRunEnd = -1
        var bestRunStart = -1
        var bestRunEnd = -1
        var bestRunLength = 0
        for (y in scanTop until scanBottom) {
            val pixel = bitmap.getPixel(x, y)
            if (isScrollbarGray(pixel)) {
                if (currentRunStart < 0) {
                    currentRunStart = y
                }
                currentRunEnd = y
            } else {
                if (currentRunStart >= 0) {
                    val runLength = currentRunEnd - currentRunStart + 1
                    if (runLength > bestRunLength) {
                        bestRunLength = runLength
                        bestRunStart = currentRunStart
                        bestRunEnd = currentRunEnd
                    }
                }
                currentRunStart = -1
                currentRunEnd = -1
            }
        }
        if (currentRunStart >= 0) {
            val runLength = currentRunEnd - currentRunStart + 1
            if (runLength > bestRunLength) {
                bestRunLength = runLength
                bestRunStart = currentRunStart
                bestRunEnd = currentRunEnd
            }
        }
        val scanHeight = scanBottom - scanTop
        val minHeight = (scanHeight * MIN_SCROLLBAR_HEIGHT_RATIO).toInt()
        val maxHeight = (scanHeight * MAX_SCROLLBAR_HEIGHT_RATIO).toInt()
        if (bestRunLength < minHeight || bestRunLength > maxHeight) {
            return LineAnalysisResult(0, -1, -1)
        }
        return LineAnalysisResult(
            score = bestRunLength,
            thumbTop = bestRunStart,
            thumbBottom = bestRunEnd
        )
    }
    private fun isScrollbarGray(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val maxDiff = maxOf(abs(r - g), abs(g - b), abs(r - b))
        if (maxDiff > SCROLLBAR_GRAY_TOLERANCE) return false
        val avg = (r + g + b) / 3
        return avg in MIN_GRAY_VALUE..MAX_GRAY_VALUE
    }
    fun calculateScrollProgress(info: ScrollbarInfo): Float {
        if (!info.found) return 0f
        val trackHeight = info.trackBottom - info.trackTop
        val thumbHeight = info.thumbBottom - info.thumbTop
        val availableTravel = trackHeight - thumbHeight
        if (availableTravel <= 0) return 1f
        val thumbPosition = info.thumbTop - info.trackTop
        return (thumbPosition.toFloat() / availableTravel).coerceIn(0f, 1f)
    }
}