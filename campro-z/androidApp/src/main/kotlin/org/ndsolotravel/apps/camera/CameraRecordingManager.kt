package org.ndsolotravel.apps.camera

import android.content.Context
import android.media.CamcorderProfile
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Robust recording engine managing file lifecycle, MediaRecorder configuration,
 * audio synchronization, file finalization, and state progression.
 */
class CameraRecordingManager(private val context: Context) {

    enum class RecordingState {
        IDLE,
        STARTING,
        RECORDING,
        STOPPING,
        FINALIZED,
        ERROR
    }

    @Volatile var state: RecordingState = RecordingState.IDLE
        private set

    private var mediaRecorder: MediaRecorder? = null
    var recordingSurface: Surface? = null
        private set

    private var currentOutputFile: File? = null
    private var recordStartTime = 0L

    val isRecording: Boolean get() = state == RecordingState.RECORDING
    val currentDurationSeconds: Long
        get() = if (isRecording) ((SystemClock.elapsedRealtime() - recordStartTime) / 1000L) else 0L

    val currentRecordedBytes: Long
        get() = currentOutputFile?.length() ?: 0L

    val currentFileName: String?
        get() = currentOutputFile?.name

    fun prepareRecorder(
        videoWidth: Int = 1920,
        videoHeight: Int = 1080,
        frameRate: Int = 30,
        bitRate: Int = 18_000_000, // 18 Mbps high-quality 1080p
        enableAudio: Boolean = true
    ): Surface {
        release()
        state = RecordingState.STARTING

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        val outputDir = getRecordingDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val targetFile = File(outputDir, "Take_$timestamp.mp4")
        currentOutputFile = targetFile

        try {
            if (enableAudio) {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(targetFile.absolutePath)
            recorder.setVideoEncodingBitRate(bitRate)
            recorder.setVideoFrameRate(frameRate)
            recorder.setVideoSize(videoWidth, videoHeight)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

            if (enableAudio) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioEncodingBitRate(192_000)
                recorder.setAudioSamplingRate(48_000)
                recorder.setAudioChannels(2)
            }

            recorder.prepare()
            mediaRecorder = recorder
            val surface = recorder.surface
            recordingSurface = surface
            Log.d("RecordingManager", "Prepared recorder for ${targetFile.name} ($videoWidth x $videoHeight)")
            return surface
        } catch (e: Exception) {
            state = RecordingState.ERROR
            Log.e("RecordingManager", "Failed to prepare recorder: ${e.message}", e)
            release()
            throw e
        }
    }

    fun startRecording() {
        val recorder = mediaRecorder ?: throw IllegalStateException("Recorder not prepared")
        try {
            recorder.start()
            recordStartTime = SystemClock.elapsedRealtime()
            state = RecordingState.RECORDING
            Log.i("RecordingManager", "Recording started successfully: ${currentOutputFile?.name}")
        } catch (e: Exception) {
            state = RecordingState.ERROR
            Log.e("RecordingManager", "Failed to start recording: ${e.message}", e)
            release()
            throw e
        }
    }

    fun stopRecording(): File? {
        if (state != RecordingState.RECORDING) {
            Log.w("RecordingManager", "Cannot stop recording: not in RECORDING state ($state)")
            return null
        }

        state = RecordingState.STOPPING
        val recorder = mediaRecorder
        val file = currentOutputFile

        try {
            recorder?.stop()
            recorder?.reset()
            state = RecordingState.FINALIZED
            Log.i("RecordingManager", "Recording finalized: ${file?.name}, size=${file?.length()} bytes")
        } catch (e: Exception) {
            state = RecordingState.ERROR
            Log.e("RecordingManager", "Error stopping recorder: ${e.message}", e)
        } finally {
            release()
        }
        return file
    }

    private fun getRecordingDirectory(): File {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "recordings")
        if (!moviesDir.exists()) {
            moviesDir.mkdirs()
        }
        return moviesDir
    }

    fun release() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        recordingSurface = null
        if (state != RecordingState.FINALIZED) {
            state = RecordingState.IDLE
        }
    }
}
