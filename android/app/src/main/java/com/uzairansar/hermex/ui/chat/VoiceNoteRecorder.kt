package com.uzairansar.hermex.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Relay-derived endpointing: do not end a turn until speech has started and
 * the trailing low-energy window has elapsed. A hard ceiling still bounds a
 * held-open microphone and is deliberately separate from silence detection.
 */
data class VoiceEndpointingPolicy(
    val silenceThreshold: Float = 0.035f,
    val trailingSilenceMillis: Long = 900L,
    val maximumDurationMillis: Long = 60_000L,
) {
    private var speechStarted = false
    private var silenceStartedAt: Long? = null

    fun reset() {
        speechStarted = false
        silenceStartedAt = null
    }

    fun update(amplitude: Float, elapsedMillis: Long): VoiceEndpointDecision {
        if (elapsedMillis >= maximumDurationMillis) return VoiceEndpointDecision.MaximumDuration
        if (amplitude >= silenceThreshold) {
            speechStarted = true
            silenceStartedAt = null
            return VoiceEndpointDecision.Continue
        }
        if (!speechStarted) return VoiceEndpointDecision.Continue
        val silenceStarted = silenceStartedAt ?: elapsedMillis.also { silenceStartedAt = it }
        return if (elapsedMillis - silenceStarted >= trailingSilenceMillis) {
            VoiceEndpointDecision.TrailingSilence
        } else {
            VoiceEndpointDecision.Continue
        }
    }

    val hasSpeech: Boolean get() = speechStarted
}

enum class VoiceEndpointDecision { Continue, TrailingSilence, MaximumDuration }

class VoiceNoteRecorder(
    private val context: Context,
) {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val MAXIMUM_FILE_BYTES = 25L * 1024L * 1024L
        private const val MAXIMUM_DURATION_MILLIS = 5 * 60_000L
        private const val MINIMUM_PCM_BYTES = SAMPLE_RATE / 5 * BYTES_PER_SAMPLE
    }

    private var recorder: AudioRecord? = null
    private var outputFile: File? = null
    private var readThread: Thread? = null
    private var readDone: CountDownLatch? = null
    private var pcmBuffer = ByteArrayOutputStream(SAMPLE_RATE * BYTES_PER_SAMPLE * 4)
    private val stopRequested = AtomicBoolean(false)
    private var endpointingPolicy: VoiceEndpointingPolicy? = null
    private var endpointCallback: (() -> Unit)? = null
    private var startedAtElapsedMillis = 0L
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    var lastErrorMessage: String? = null
        private set

    val isRecording: Boolean
        get() = recorder != null && outputFile != null

    @SuppressLint("MissingPermission")
    fun start(
        onLimitReached: () -> Unit = {},
        onSilenceLimitReached: () -> Unit = {},
        endpointing: VoiceEndpointingPolicy? = null,
    ): File {
        stop(delete = true)
        lastErrorMessage = null
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE / 10 * BYTES_PER_SAMPLE)
        val file = File(context.cacheDir, "voice-note-${System.currentTimeMillis()}.wav")
        val nextRecorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .build()
        return try {
            check(nextRecorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            nextRecorder.startRecording()
            synchronized(this) {
                recorder = nextRecorder
                outputFile = file
                pcmBuffer = ByteArrayOutputStream(SAMPLE_RATE * BYTES_PER_SAMPLE * 4)
                stopRequested.set(false)
                endpointingPolicy = endpointing
                endpointCallback = onSilenceLimitReached
                startedAtElapsedMillis = SystemClock.elapsedRealtime()
                _amplitude.value = 0f
                val done = CountDownLatch(1)
                readDone = done
                readThread = Thread({ readLoop(nextRecorder, minBuffer, onLimitReached, done) }, "HermexVoiceRecorder")
                    .also { it.start() }
            }
            file
        } catch (error: Throwable) {
            runCatching { nextRecorder.release() }
            runCatching { file.delete() }
            throw error
        }
    }

    fun stop(delete: Boolean = false): File? {
        val file = outputFile
        val current = recorder
        recorder = null
        outputFile = null
        stopRequested.set(true)
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
        readDone?.await(1, TimeUnit.SECONDS)
        readDone = null
        val pcm = synchronized(this) { pcmBuffer.toByteArray() }
        if (delete) {
            file?.delete()
            return null
        }
        if (file == null || pcm.size < MINIMUM_PCM_BYTES || pcm.size > MAXIMUM_FILE_BYTES) {
            lastErrorMessage = if (pcm.size > MAXIMUM_FILE_BYTES) {
                "Voice notes must be 25 MB or smaller."
            } else {
                "Voice note was empty. Speak before stopping and try again."
            }
            file?.delete()
            return null
        }
        writeWav(file, pcm)
        return file.takeIf { it.exists() && it.length() in 45L..(MAXIMUM_FILE_BYTES + 44L) }
    }

    private fun readLoop(
        current: AudioRecord,
        minBuffer: Int,
        onLimitReached: () -> Unit,
        done: CountDownLatch,
    ) {
        val buffer = ByteArray(minBuffer)
        var endpoint: VoiceEndpointDecision = VoiceEndpointDecision.Continue
        try {
            while (!stopRequested.get()) {
                val read = current.read(buffer, 0, buffer.size)
                if (read <= 0) break
                var reachedFileLimit = false
                synchronized(this) {
                    if (pcmBuffer.size() + read <= MAXIMUM_FILE_BYTES) pcmBuffer.write(buffer, 0, read)
                    else reachedFileLimit = true
                }
                if (reachedFileLimit) {
                    endpoint = VoiceEndpointDecision.MaximumDuration
                    break
                }
                val amplitude = rmsAmplitude(buffer, read)
                _amplitude.value = amplitude
                val elapsedMillis = SystemClock.elapsedRealtime() - startedAtElapsedMillis
                endpoint = endpointingPolicy?.update(
                    amplitude,
                    elapsedMillis,
                ) ?: if (elapsedMillis >= MAXIMUM_DURATION_MILLIS) {
                    VoiceEndpointDecision.MaximumDuration
                } else {
                    VoiceEndpointDecision.Continue
                }
                if (endpoint != VoiceEndpointDecision.Continue) break
            }
        } finally {
            _amplitude.value = 0f
            done.countDown()
            when (endpoint) {
                VoiceEndpointDecision.TrailingSilence -> endpointCallback?.invoke()
                VoiceEndpointDecision.MaximumDuration -> onLimitReached()
                VoiceEndpointDecision.Continue -> Unit
            }
        }
    }

    private fun rmsAmplitude(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
            val sample = ((buffer[index + 1].toInt() shl 8) or (buffer[index].toInt() and 0xff)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            count++
            index += BYTES_PER_SAMPLE
        }
        return if (count == 0) 0f else (sqrt(sum / count) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    private fun writeWav(file: File, pcm: ByteArray) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            output.writeBytes("RIFF")
            output.writeInt(Integer.reverseBytes(36 + pcm.size))
            output.writeBytes("WAVEfmt ")
            output.writeInt(Integer.reverseBytes(16))
            output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE))
            output.writeInt(Integer.reverseBytes(SAMPLE_RATE * BYTES_PER_SAMPLE))
            output.writeShort(java.lang.Short.reverseBytes(BYTES_PER_SAMPLE.toShort()).toInt())
            output.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
            output.writeBytes("data")
            output.writeInt(Integer.reverseBytes(pcm.size))
            output.write(pcm)
        }
    }
}
