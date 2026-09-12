package com.samsung.screenrecorder.service

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.MediaStore
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.samsung.screenrecorder.MainActivity
import com.samsung.screenrecorder.R
import com.samsung.screenrecorder.util.SamsungCompatibilityManager
import java.io.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class ScreenRecordService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var isRecording = false
    private var isPaused = false

    private var videoTempFile: File? = null
    private var finalUri: Uri? = null
    private var videoWidth = 1080
    private var videoHeight = 1920

    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    @Volatile private var audioPaused = false
    private var audioEncoder: MediaCodec? = null
    private var audioOutputFormat: MediaFormat? = null
    private var audioPacketFile: File? = null
    private var micRecord: AudioRecord? = null
    private var playbackRecord: AudioRecord? = null
    private var audioSourceMode = "mute"
    private var audioSampleRate = 44_100
    private var audioChannels = 1

    companion object {
        const val ACTION_START = "com.samsung.screenrecorder.ACTION_START"
        const val ACTION_PAUSE = "com.samsung.screenrecorder.ACTION_PAUSE"
        const val ACTION_RESUME = "com.samsung.screenrecorder.ACTION_RESUME"
        const val ACTION_STOP = "com.samsung.screenrecorder.ACTION_STOP"
        const val ACTION_STATE = "com.samsung.screenrecorder.ACTION_STATE"
        const val EXTRA_STATE = "extra_state"
        const val EXTRA_ERROR = "extra_error"
        const val STATE_STARTED = "started"
        const val STATE_PAUSED = "paused"
        const val STATE_RESUMED = "resumed"
        const val STATE_STOPPED = "stopped"
        const val STATE_ERROR = "error"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_CODEC = "extra_codec"
        const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "samsung_screen_recorder_channel"
        private const val NOTIFICATION_ID = 1001
        private const val AUDIO_MIME = "audio/mp4a-latm"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (isRecording) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            broadcastState(STATE_ERROR, "Screen capture permission was not granted")
            stopSelf()
            return
        }
        startForegroundCompat(buildNotification("Preparing recording…"))
        try {
            startRecording(intent, resultCode, resultData)
            updateNotification("Recording in progress…")
            broadcastState(STATE_STARTED)
        } catch (t: Throwable) {
            Log.e(TAG, "Recording failed to start", t)
            cleanup(false)
            broadcastState(STATE_ERROR, t.message ?: "Unable to start recording")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startRecording(intent: Intent, resultCode: Int, resultData: Intent) {
        val width = even(intent.getIntExtra(EXTRA_WIDTH, 1080))
        val height = even(intent.getIntExtra(EXTRA_HEIGHT, 1920))
        val dpi = intent.getIntExtra(EXTRA_DPI, 420).coerceAtLeast(1)
        val bitrate = intent.getIntExtra(EXTRA_BITRATE, 12_000_000).coerceIn(2_000_000, 35_000_000)
        val fps = intent.getIntExtra(EXTRA_FPS, 30).coerceIn(15, 60)
        val requestedCodec = intent.getStringExtra(EXTRA_CODEC) ?: "AUTO"
        audioSourceMode = intent.getStringExtra(EXTRA_AUDIO_SOURCE) ?: "mute"
        videoWidth = width
        videoHeight = height

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjection unavailable")

        projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (isRecording) stopRecording()
            }
        }
        mediaProjection!!.registerCallback(projectionCallback!!, Handler(Looper.getMainLooper()))

        videoTempFile = File(cacheDir, "screen_${System.currentTimeMillis()}.mp4")
        configureVideoRecorder(videoTempFile!!, width, height, bitrate, fps, requestedCodec)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "SamsungScreenRecorderDisplay", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface, null, null
        ) ?: throw IllegalStateException("Unable to create virtual display")

        mediaRecorder!!.start()
        isRecording = true
        isPaused = false

        if (audioSourceMode != "mute") startAudioCapture()
    }

    private fun configureVideoRecorder(file: File, width: Int, height: Int, bitrate: Int, fps: Int, codec: String) {
        fun create(useHevc: Boolean, frameRate: Int): MediaRecorder {
            val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(file.absolutePath)
                setVideoSize(width, height)
                setVideoEncoder(if (useHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(bitrate)
                setVideoFrameRate(frameRate)
                prepare()
            }
            return recorder
        }
        val wantHevc = codec.equals("HEVC", true) && SamsungCompatibilityManager.supportsHardwareHevc()
        val attempts = listOf(Pair(wantHevc, fps), Pair(false, fps), Pair(false, 30)).distinct()
        var lastError: Throwable? = null
        for ((useHevc, frameRate) in attempts) {
            try {
                file.delete()
                mediaRecorder = create(useHevc, frameRate)
                return
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "Video profile failed: codec=${if (useHevc) "HEVC" else "H264"}, fps=$frameRate", t)
            }
        }
        throw lastError ?: IllegalStateException("No supported video profile")
    }

    private fun startAudioCapture() {
        val wantsPlayback = audioSourceMode == "internal" || audioSourceMode == "both"
        val wantsMic = audioSourceMode == "mic" || audioSourceMode == "both"
        if (wantsMic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is required")
        }

        audioSampleRate = 44_100
        audioChannels = 1
        val minBuffer = max(
            AudioRecord.getMinBufferSize(audioSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            audioSampleRate / 2
        )

        if (wantsMic) {
            micRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(audioSampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        }
        if (wantsPlayback) {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            playbackRecord = AudioRecord.Builder()
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(audioSampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(minBuffer * 2)
                .build()
        }

        val format = MediaFormat.createAudioFormat(AUDIO_MIME, audioSampleRate, audioChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuffer)
        }
        audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
        audioPacketFile = File(cacheDir, "audio_${System.currentTimeMillis()}.packets")
        audioRunning = true
        micRecord?.startRecording()
        playbackRecord?.startRecording()
        audioThread = Thread { audioLoop(wantsMic, wantsPlayback, minBuffer) }.also { it.start() }
    }

    private fun audioLoop(wantsMic: Boolean, wantsPlayback: Boolean, bufferSize: Int) {
        val micBuffer = ShortArray(bufferSize / 2)
        val playbackBuffer = ShortArray(bufferSize / 2)
        val mixed = ShortArray(bufferSize / 2)
        var totalSamples = 0L
        try {
            while (audioRunning) {
                if (audioPaused) { Thread.sleep(20); continue }
                val micRead = if (wantsMic) micRecord?.read(micBuffer, 0, micBuffer.size) ?: 0 else 0
                val playRead = if (wantsPlayback) playbackRecord?.read(playbackBuffer, 0, playbackBuffer.size) ?: 0 else 0
                val count = max(micRead, playRead)
                if (count <= 0) continue
                for (i in 0 until count) {
                    val m = if (i < micRead) micBuffer[i].toInt() else 0
                    val p = if (i < playRead) playbackBuffer[i].toInt() else 0
                    mixed[i] = ((m + p) / 2).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                queueAudio(mixed, count, totalSamples)
                totalSamples += count
                drainAudioEncoder(false)
            }
            queueAudioEnd(totalSamples)
            drainAudioEncoder(true)
        } catch (t: Throwable) {
            Log.e(TAG, "Audio capture failed", t)
        }
    }

    private fun queueAudio(samples: ShortArray, count: Int, sampleOffset: Long) {
        val codec = audioEncoder ?: return
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        for (i in 0 until count) input.putShort(samples[i])
        val ptsUs = sampleOffset * 1_000_000L / audioSampleRate
        codec.queueInputBuffer(index, 0, count * 2, ptsUs, 0)
    }

    private fun queueAudioEnd(sampleOffset: Long) {
        val codec = audioEncoder ?: return
        val index = codec.dequeueInputBuffer(10_000)
        if (index >= 0) codec.queueInputBuffer(index, 0, 0, sampleOffset * 1_000_000L / audioSampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun drainAudioEncoder(endOfStream: Boolean) {
        val codec = audioEncoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> audioOutputFormat = codec.outputFormat
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return else continue
                index >= 0 -> {
                    val out = codec.getOutputBuffer(index)
                    if (out != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val packetFile = audioPacketFile ?: return
                        DataOutputStream(BufferedOutputStream(FileOutputStream(packetFile, true))).use { dos ->
                            val data = ByteArray(info.size)
                            out.position(info.offset); out.limit(info.offset + info.size); out.get(data)
                            dos.writeLong(info.presentationTimeUs)
                            dos.writeInt(data.size)
                            dos.write(data)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        try {
            mediaRecorder?.pause()
            isPaused = true
            audioPaused = true
            broadcastState(STATE_PAUSED)
            updateNotification("Recording paused")
        } catch (t: Throwable) { Log.e(TAG, "Pause failed", t) }
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        try {
            mediaRecorder?.resume()
            isPaused = false
            if (audioSourceMode != "mute") restartAudioAfterPause()
            broadcastState(STATE_RESUMED)
            updateNotification("Recording in progress…")
        } catch (t: Throwable) { Log.e(TAG, "Resume failed", t) }
    }

    private fun restartAudioAfterPause() {
        audioPaused = false
    }

    private fun stopRecording() {
        if (!isRecording && mediaRecorder == null) { stopSelf(); return }
        var success = false
        try {
            audioRunning = false
            audioPaused = false
            try { audioThread?.join(1500) } catch (_: Throwable) {}
            try { micRecord?.stop() } catch (_: Throwable) {}
            try { playbackRecord?.stop() } catch (_: Throwable) {}
            try { audioEncoder?.stop() } catch (_: Throwable) {}
            try { audioEncoder?.release() } catch (_: Throwable) {}
            audioEncoder = null

            if (isRecording) {
                try { mediaRecorder?.stop(); success = videoTempFile?.exists() == true && videoTempFile!!.length() > 0 } catch (t: Throwable) { Log.e(TAG, "Video stop failed", t) }
            }
            if (success) {
                if (audioSourceMode != "mute" && audioOutputFormat != null && audioPacketFile?.length() ?: 0 > 0) {
                    finalUri = createFinalMediaStoreFile()
                    muxVideoAndAudio(videoTempFile!!, audioPacketFile!!, audioOutputFormat!!, finalUri!!)
                } else {
                    finalUri = copyVideoToMediaStore(videoTempFile!!)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Stop failed", t)
            success = false
        } finally {
            cleanup(success)
            broadcastState(if (success) STATE_STOPPED else STATE_ERROR, if (!success) "Recording could not be saved" else null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createFinalMediaStoreFile(): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Samsung_Rec_${timestamp()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ScreenRecordings")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create gallery output")
    }

    private fun copyVideoToMediaStore(file: File): Uri {
        val uri = createFinalMediaStoreFile()
        contentResolver.openOutputStream(uri, "w")!!.use { out -> FileInputStream(file).use { it.copyTo(out) } }
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun muxVideoAndAudio(video: File, packets: File, audioFormat: MediaFormat, uri: Uri) {
        val descriptor = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("Unable to open final output")
        val muxer = MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(video.absolutePath)
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { extractor.selectTrack(i); videoTrack = muxer.addTrack(f); break }
            }
            if (videoTrack < 0) throw IllegalStateException("No video track")
            val audioTrack = muxer.addTrack(audioFormat)
            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0; info.size = size; info.presentationTimeUs = extractor.sampleTime; info.flags = extractor.sampleFlags
                muxer.writeSampleData(videoTrack, buffer, info)
                extractor.advance()
            }
            DataInputStream(BufferedInputStream(FileInputStream(packets))).use { input ->
                while (true) {
                    val pts = try { input.readLong() } catch (_: EOFException) { break }
                    val size = input.readInt()
                    if (size <= 0 || size > 2_000_000) throw IOException("Invalid audio packet")
                    val data = ByteArray(size); input.readFully(data)
                    buffer.clear(); buffer.put(data); buffer.flip()
                    info.offset = 0; info.size = size; info.presentationTimeUs = pts; info.flags = 0
                    muxer.writeSampleData(audioTrack, buffer, info)
                }
            }
            extractor.release()
        } finally {
            muxer.release()
        }
        descriptor.close()
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    }

    private fun cleanup(keepOutput: Boolean) {
        try { projectionCallback?.let { mediaProjection?.unregisterCallback(it) } } catch (_: Throwable) {}
        projectionCallback = null
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        try { mediaRecorder?.release() } catch (_: Throwable) {}
        try { micRecord?.release() } catch (_: Throwable) {}
        try { playbackRecord?.release() } catch (_: Throwable) {}
        try { audioEncoder?.release() } catch (_: Throwable) {}
        if (!keepOutput) finalUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Throwable) {} }
        try { videoTempFile?.delete() } catch (_: Throwable) {}
        try { audioPacketFile?.delete() } catch (_: Throwable) {}
        mediaRecorder = null; mediaProjection = null; virtualDisplay = null
        micRecord = null; playbackRecord = null; audioThread = null
        videoTempFile = null; audioPacketFile = null; finalUri = null
        audioOutputFormat = null; isRecording = false; isPaused = false; audioRunning = false
    }

    override fun onDestroy() { if (isRecording) stopRecording() else cleanup(false); super.onDestroy() }

    private fun broadcastState(state: String, error: String? = null) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_STATE, state); if (error != null) putExtra(EXTRA_ERROR, error)
        })
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Samsung Screen Recorder", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Samsung Screen Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(isRecording || text.contains("Preparing"))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    private fun even(v: Int): Int = (v.coerceAtLeast(2) / 2) * 2
}
