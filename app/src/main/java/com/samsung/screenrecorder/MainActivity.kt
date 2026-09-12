package com.samsung.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.*
import com.samsung.screenrecorder.service.ScreenRecordService
import com.samsung.screenrecorder.ui.ScreenRecorderScreen
import com.samsung.screenrecorder.ui.theme.SamsungScreenRecorderTheme

class MainActivity : ComponentActivity() {
    private var isRecording by mutableStateOf(false)
    private var isPaused by mutableStateOf(false)
    private var pendingResolution by mutableStateOf("1080p")
    private var pendingFps by mutableStateOf(60)
    private var pendingAudioSource by mutableStateOf("both")

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) startServiceRecording(result.data!!)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val micNeeded = pendingAudioSource == "mic" || pendingAudioSource == "both"
        val micGranted = !micNeeded || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) requestScreenCapture()
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(ScreenRecordService.EXTRA_STATE)) {
                ScreenRecordService.STATE_STARTED, ScreenRecordService.STATE_RESUMED -> { isRecording = true; isPaused = false }
                ScreenRecordService.STATE_PAUSED -> { isRecording = true; isPaused = true }
                ScreenRecordService.STATE_STOPPED, ScreenRecordService.STATE_ERROR -> { isRecording = false; isPaused = false }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(this, stateReceiver, IntentFilter(ScreenRecordService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            SamsungScreenRecorderTheme {
                ScreenRecorderScreen(isRecording, isPaused,
                    onStartRecording = { resolution, fps, audio -> pendingResolution = resolution; pendingFps = fps; pendingAudioSource = audio; requestRequiredPermissions() },
                    onPauseRecording = { sendAction(ScreenRecordService.ACTION_PAUSE) },
                    onResumeRecording = { sendAction(ScreenRecordService.ACTION_RESUME) },
                    onStopRecording = { sendAction(ScreenRecordService.ACTION_STOP) }
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        if (pendingAudioSource == "mic" || pendingAudioSource == "both") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray()) else requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startServiceRecording(data: Intent) {
        val metrics = resources.displayMetrics
        val (width, height) = resolutionFor(metrics.widthPixels, metrics.heightPixels, pendingResolution)
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordService.EXTRA_WIDTH, width)
            putExtra(ScreenRecordService.EXTRA_HEIGHT, height)
            putExtra(ScreenRecordService.EXTRA_DPI, metrics.densityDpi)
            putExtra(ScreenRecordService.EXTRA_BITRATE, bitrateFor(pendingResolution, pendingFps, width, height))
            putExtra(ScreenRecordService.EXTRA_FPS, pendingFps)
            putExtra(ScreenRecordService.EXTRA_CODEC, "HEVC")
            putExtra(ScreenRecordService.EXTRA_AUDIO_SOURCE, pendingAudioSource)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendAction(action: String) { startService(Intent(this, ScreenRecordService::class.java).setAction(action)) }

    private fun resolutionFor(screenWidth: Int, screenHeight: Int, selected: String): Pair<Int, Int> {
        val portrait = screenHeight >= screenWidth
        val sourceShort = if (portrait) screenWidth else screenHeight
        val targetShort = when (selected) { "1440p" -> 1440; "720p" -> 720; else -> 1080 }.coerceAtMost(sourceShort)
        val scale = targetShort.toFloat() / sourceShort.toFloat()
        val w = if (portrait) screenWidth * scale else screenHeight * scale
        val h = if (portrait) screenHeight * scale else screenWidth * scale
        return even(w.toInt()) to even(h.toInt())
    }

    private fun bitrateFor(resolution: String, fps: Int, width: Int, height: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val base = when {
            pixels >= 3_000_000L -> 20_000_000
            pixels >= 1_500_000L -> 12_000_000
            else -> 7_000_000
        }
        return if (fps >= 60) base else (base * 0.75).toInt()
    }

    private fun even(v: Int) = (v.coerceAtLeast(2) / 2) * 2

    override fun onDestroy() {
        unregisterReceiver(stateReceiver)
        super.onDestroy()
    }
}
