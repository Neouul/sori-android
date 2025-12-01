package com.misterjerry.test01.data

import android.content.Context
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate

class AudioClassifierHelper(private val context: Context) {
    private var audioClassifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _classificationFlow = MutableSharedFlow<Pair<String, Float>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val classificationFlow: SharedFlow<Pair<String, Float>> = _classificationFlow.asSharedFlow()

    fun startAudioClassification() {
        if (isRecording) return
        isRecording = true

        scope.launch {
            try {
                if (audioClassifier == null) {
                    audioClassifier = AudioClassifier.createFromFile(context, "yamnet.tflite")
                }
                val classifier = audioClassifier ?: return@launch

                if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return@launch
                }

                // Use CAMCORDER for best stereo compatibility
                val audioSource = android.media.MediaRecorder.AudioSource.CAMCORDER
                
                // Use 48kHz which is standard for Android devices and likely to support stereo
                val sampleRate = 48000
                val targetSampleRate = 16000 // YAMNet requirement
                val downsampleFactor = sampleRate / targetSampleRate // 3
                
                val channelConfig = android.media.AudioFormat.CHANNEL_IN_STEREO
                val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val bufferSize = maxOf(minBufferSize, sampleRate * 2) // 2 seconds buffer

                audioRecord = AudioRecord(
                    audioSource,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                audioRecord?.startRecording()

                val buffer = ShortArray(bufferSize)
                val audioTensor = classifier.createInputTensorAudio()
                
                // YAMNet input size (0.975s at 16kHz) -> 15600 samples
                val requiredModelSamples = 15600
                val monoBuffer = FloatArray(requiredModelSamples)
                
                while (isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult > 0) {
                        val frames = readResult / 2
                        val leftChannel = FloatArray(frames)
                        val rightChannel = FloatArray(frames)
                        
                        // De-interleave
                        for (i in 0 until frames) {
                            leftChannel[i] = buffer[i * 2] / 32768f
                            rightChannel[i] = buffer[i * 2 + 1] / 32768f
                        }

                        // We need enough frames to fill the model buffer after downsampling
                        // requiredFrames = 15600 * 3 = 46800 frames (approx 1 sec)
                        // If our buffer is smaller, we might need to accumulate. 
                        // For now, let's assume we read enough or just process what we have if it's sufficient.
                        
                        val availableModelSamples = frames / downsampleFactor
                        
                        if (availableModelSamples >= requiredModelSamples) {
                            // Mix down and Downsample for Classifier
                            for (i in 0 until requiredModelSamples) {
                                val srcIndex = i * downsampleFactor
                                // Simple average of L+R at the sample point
                                val leftSample = leftChannel[frames - (requiredModelSamples * downsampleFactor) + srcIndex]
                                val rightSample = rightChannel[frames - (requiredModelSamples * downsampleFactor) + srcIndex]
                                monoBuffer[i] = (leftSample + rightSample) / 2f
                            }
                            
                            audioTensor.load(monoBuffer, 0, requiredModelSamples)
                            val output = classifier.classify(audioTensor)
                            
                            val filteredResult = output[0].categories.filter { it.score > 0.3f }
                            
                            if (filteredResult.isNotEmpty()) {
                                val topResult = filteredResult.sortedByDescending { it.score }[0]
                                
                                // Calculate Direction using the high-res 48kHz data
                                // We use the corresponding segment of the original high-res channels
                                val segmentLength = requiredModelSamples * downsampleFactor
                                val startIdx = frames - segmentLength
                                
                                val leftSegment = leftChannel.copyOfRange(startIdx, frames)
                                val rightSegment = rightChannel.copyOfRange(startIdx, frames)
                                
                                val direction = calculateDirection(leftSegment, rightSegment)
                                
                                Log.d("AudioClassifier", "Result: ${topResult.label} (${topResult.score}), Dir: $direction")
                                _classificationFlow.tryEmit(topResult.label to direction)
                            }
                        }
                    }
                    delay(100)
                }

            } catch (e: Exception) {
                Log.e("AudioClassifier", "Error in classification loop", e)
            }
        }
    }

    private fun calculateDirection(left: FloatArray, right: FloatArray): Float {
        // Simple Cross-Correlation
        // At 48kHz, 1ms = 48 samples. 
        // Max width ~20cm -> ~0.6ms -> ~30 samples.
        val maxLag = 40 
        var bestLag = 0
        var maxCorr = -Float.MAX_VALUE
        
        for (lag in -maxLag..maxLag) {
            var corr = 0f
            // Optimization: Don't correlate the entire 1 second, just the center or loud parts?
            // For now, correlate entire buffer (might be slow).
            // Let's optimize by taking a stride or smaller window if needed. 
            // But 48000 muls * 80 lags = 3.8M ops. Doable in modern CPU but maybe heavy.
            // Let's use a step of 2 for the loop or just process.
            
            val start = maxOf(0, -lag)
            val end = minOf(left.size, right.size - lag)
            
            // Simple optimization: only sum every 2nd sample to speed up
            for (i in start until end step 2) {
                corr += left[i] * right[i + lag]
            }
            
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }
        
        val normalizedLag = bestLag.coerceIn(-maxLag, maxLag).toFloat()
        val angle = -(normalizedLag / maxLag) * 90f
        
        return angle
    }

    fun stopAudioClassification() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
