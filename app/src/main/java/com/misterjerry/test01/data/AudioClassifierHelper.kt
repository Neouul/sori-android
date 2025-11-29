package com.misterjerry.test01.data

import android.content.Context
import android.media.AudioRecord
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.util.Timer
import kotlin.concurrent.scheduleAtFixedRate

class AudioClassifierHelper(private val context: Context) {
    private var audioClassifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var timer: Timer? = null

    private val _classificationFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val classificationFlow: SharedFlow<String> = _classificationFlow.asSharedFlow()

    fun startAudioClassification() {
        try {
            if (audioClassifier == null) {
                audioClassifier = AudioClassifier.createFromFile(context, "yamnet.tflite")
            }

            val classifier = audioClassifier ?: return
            val audioTensor = classifier.createInputTensorAudio()

            audioRecord = classifier.createAudioRecord()
            audioRecord?.startRecording()

            timer = Timer()
            timer?.scheduleAtFixedRate(0, 500) { // Classify every 500ms
                val record = audioRecord ?: return@scheduleAtFixedRate
                audioTensor.load(record)
                val output = classifier.classify(audioTensor)
                
                val filteredResult = output[0].categories.filter { it.score > 0.3f } // Threshold 0.3
                
                if (filteredResult.isNotEmpty()) {
                    val topResult = filteredResult.sortedByDescending { it.score }[0]
                    Log.d("AudioClassifier", "Result: ${topResult.label} (${topResult.score})")
                    _classificationFlow.tryEmit(topResult.label)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioClassifier", "Error initializing classifier", e)
        }
    }

    fun stopAudioClassification() {
        timer?.cancel()
        timer = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        // Do not close classifier here if we want to reuse it, or close it if we want to save memory
        // audioClassifier?.close() 
    }
}
