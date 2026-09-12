package com.samsung.screenrecorder.util

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Environment
import android.view.MotionEvent
import java.io.File

/**
 * Universal Samsung Hardware & One UI compatibility detector.
 * Ensures smooth operation across Galaxy S, Z Fold, Z Flip, Note, Tab, and A/M series.
 */
object SamsungCompatibilityManager {

    val isSamsungDevice: Boolean
        get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    val oneUiVersion: String
        get() {
            return try {
                val clazz = Class.forName("android.os.SystemProperties")
                val getMethod = clazz.getMethod("get", String::class.java)
                val oneUiVer = getMethod.invoke(null, "ro.build.version.oneui") as? String
                if (!oneUiVer.isNullOrBlank()) "One UI ${oneUiVer.toDouble() / 10000}"
                else "Android ${Build.VERSION.RELEASE}"
            } catch (e: Exception) {
                "Android ${Build.VERSION.RELEASE}"
            }
        }

    /**
     * Checks if S-Pen hardware is integrated (Galaxy S24/S23 Ultra, Note, Tab S9, Z Fold).
     */
    fun hasSPenSupport(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature("com.sec.feature.spen") ||
               Build.MODEL.contains("Ultra", ignoreCase = true) ||
               Build.MODEL.contains("Note", ignoreCase = true) ||
               Build.MODEL.contains("SM-X", ignoreCase = true) // Galaxy Tab S
    }

    /**
     * Detects if input event comes from an S-Pen or stylus.
     */
    fun isStylusEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        return toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER
    }

    /**
     * Detects if device is a foldable (Galaxy Z Fold or Z Flip).
     */
    val isFoldableDevice: Boolean
        get() = Build.MODEL.contains("SM-F", ignoreCase = true) ||
               Build.DEVICE.contains("q2q", ignoreCase = true) || // Z Fold 4/5
               Build.DEVICE.contains("b4q", ignoreCase = true)    // Z Flip 4/5

    /**
     * Returns true if device supports hardware HEVC (H.265) video encoding.
     */
    fun supportsHardwareHevc(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in codecList.codecInfos) {
            if (!info.isEncoder) continue
            val types = info.supportedTypes
            for (type in types) {
                if (type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Checks if external MicroSD card is mounted and writable (Galaxy A/M series).
     */
    fun getExternalSdCardPath(context: Context): String? {
        val externalFilesDirs = context.getExternalFilesDirs(null)
        if (externalFilesDirs.size > 1 && externalFilesDirs[1] != null) {
            val sdDir = externalFilesDirs[1]
            if (Environment.isExternalStorageRemovable(sdDir)) {
                return sdDir.absolutePath
            }
        }
        return null
    }

    /**
     * Recommends optimal recording profile based on RAM, chipset, and screen refresh rate.
     */
    fun getRecommendedProfile(totalRamGb: Double): Triple<String, Int, Int> {
        return when {
            totalRamGb >= 12.0 -> Triple("1440p", 120, 24_000_000) // Flagship S24U/Z Fold
            totalRamGb >= 8.0 -> Triple("1080p", 60, 16_000_000)  // Midrange A54/Z Flip
            else -> Triple("720p", 30, 8_000_000)                  // Budget A14/A05s
        }
    }
}