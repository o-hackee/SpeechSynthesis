//
// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE.md file in the project root for full license information.
//
package com.microsoft.cognitiveservices.speech.samples.speechsynthesis

import android.Manifest.permission
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.microsoft.cognitiveservices.speech.AudioDataStream
import com.microsoft.cognitiveservices.speech.Connection
import com.microsoft.cognitiveservices.speech.ConnectionEventArgs
import com.microsoft.cognitiveservices.speech.PropertyId
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisEventArgs
import com.microsoft.cognitiveservices.speech.SpeechSynthesisOutputFormat
import com.microsoft.cognitiveservices.speech.SpeechSynthesisWordBoundaryEventArgs
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.samples.speechsynthesis.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var speechConfig: SpeechConfig? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var connection: Connection? = null
    private var audioTrack: AudioTrack? = null

    private lateinit var binding: ActivityMainBinding

    private var speakingRunnable: SpeakingRunnable? = null
    private var singleThreadExecutor: ExecutorService? = null
    private val synchronizedObj = Any()
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Note: we need to request the permissions
        val requestCode = 5 // Unique code for the permission request
        ActivityCompat.requestPermissions(this@MainActivity, arrayOf(permission.INTERNET), requestCode)

        singleThreadExecutor = Executors.newSingleThreadExecutor()
        speakingRunnable = SpeakingRunnable()

        binding.outputMessage.movementMethod = ScrollingMovementMethod()

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT) * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Release speech synthesizer and its dependencies
        if (synthesizer != null) {
            synthesizer?.close()
            connection?.close()
        }
        if (speechConfig != null) {
            speechConfig?.close()
        }

        if (audioTrack != null) {
            singleThreadExecutor?.shutdownNow()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
    }

    fun onCreateSynthesizerButtonClicked(v: View) {
        if (synthesizer != null) {
            speechConfig?.close()
            synthesizer?.close()
            connection?.close()
        }

        // Reuse the synthesizer to lower the latency.
        // I.e. create one synthesizer and speak many times using it.
        clearOutputMessage()
        updateOutputMessage("Initializing synthesizer...\n")

        speechConfig = SpeechConfig.fromSubscription(getString(R.string.speech_service_subscription_key), getString(R.string.speech_service_region))
        // Use 24k Hz format for higher quality.
        speechConfig?.setSpeechSynthesisOutputFormat(SpeechSynthesisOutputFormat.Raw24Khz16BitMonoPcm)
        // Set voice name.
        speechConfig?.speechSynthesisVoiceName = "en-US-JennyNeural"
        synthesizer = SpeechSynthesizer(speechConfig, null)
        connection = Connection.fromSpeechSynthesizer(synthesizer)

        @SuppressWarnings("deprecation")
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 
            resources.configuration.locales[0]
        else
            resources.configuration.locale

        connection!!.connected.addEventListener { o: Any?, e: ConnectionEventArgs? ->
            updateOutputMessage("Connection established.\n")
        }

        connection!!.disconnected.addEventListener { o: Any?, e: ConnectionEventArgs? ->
            updateOutputMessage("Disconnected.\n")
        }

        synthesizer!!.SynthesisStarted.addEventListener { o: Any?, e: SpeechSynthesisEventArgs ->
            updateOutputMessage(String.format(current,
                "Synthesis started. Result Id: %s.\n",
                e.result.resultId))
            e.close()
        }

        synthesizer!!.Synthesizing.addEventListener { o: Any?, e: SpeechSynthesisEventArgs ->
            updateOutputMessage(String.format(current,
                "Synthesizing. received %d bytes.\n",
                e.result.audioLength))
            e.close()
        }

        synthesizer!!.SynthesisCompleted.addEventListener { o: Any?, e: SpeechSynthesisEventArgs ->
            updateOutputMessage("Synthesis finished.\n")
            updateOutputMessage("\tFirst byte latency: ${e.result.properties.getProperty(PropertyId.SpeechServiceResponse_SynthesisFirstByteLatencyMs)} ms.\n")
            updateOutputMessage("\tFinish latency: ${e.result.properties.getProperty(PropertyId.SpeechServiceResponse_SynthesisFinishLatencyMs)} ms.\n")
            e.close()
        }

        synthesizer!!.SynthesisCanceled.addEventListener { o: Any?, e: SpeechSynthesisEventArgs ->
            val cancellationDetails =
                SpeechSynthesisCancellationDetails.fromResult(e.result).toString()
            updateOutputMessage("Error synthesizing. Result ID: ${e.result.resultId}. Error detail: ${System.lineSeparator()}$cancellationDetails${System.lineSeparator()}Did you update the subscription info?\n",
                true, true)
            e.close()
        }

        synthesizer!!.WordBoundary.addEventListener { o: Any?, e: SpeechSynthesisWordBoundaryEventArgs ->
            updateOutputMessage(String.format(current,
                "Word boundary. Text offset %d, length %d; audio offset %d ms.\n",
                e.textOffset,
                e.wordLength,
                e.audioOffset / 10000))

        }
    }

    fun onPreConnectButtonClicked(v: View) {
        // This method could pre-establish the connection to service to lower the latency
        // This method is useful when you want to synthesize audio in a short time, but the text is
        // not available. E.g. for speech bot, you can warm up the TTS connection when the user is speaking;
        // then call speak() when dialogue utterance is ready.
        if (connection == null) {
            updateOutputMessage("Please initialize the speech synthesizer first\n", true, true)
            return
        }
        connection?.openConnection(true)
        updateOutputMessage("Opening connection.\n")
    }

    fun onSpeechButtonClicked(v: View) {
        clearOutputMessage()

        if (synthesizer == null) {
            updateOutputMessage("Please initialize the speech synthesizer first\n", true, true)
            return
        }

        speakingRunnable?.setContent(binding.speakText.text.toString())
        singleThreadExecutor?.execute(speakingRunnable)
    }

    fun onStopButtonClicked(v: View) {
        if (synthesizer == null) {
            updateOutputMessage("Please initialize the speech synthesizer first\n", true, true)
            return
        }

        stopSynthesizing()
    }

    internal inner class SpeakingRunnable : Runnable {
        private var content: String? = null

        fun setContent(content: String?) {
            this.content = content
        }

        override fun run() {
            try {
                audioTrack?.play()
                synchronized (synchronizedObj) {
                    stopped = false
                }

                val result = synthesizer!!.StartSpeakingTextAsync(content).get()
                val audioDataStream = AudioDataStream.fromResult(result)

                // Set the chunk size to 50 ms. 24000 * 16 * 0.05 / 8 = 2400
                val buffer = ByteArray(2400)
                while (!stopped) {
                    val len = audioDataStream.readData(buffer)
                    if (len == 0L) {
                        break
                    }
                    audioTrack?.write(buffer, 0, len.toInt())
                }

                audioDataStream.close()
            } catch (ex: Exception) {
                Log.e("Speech Synthesis Demo", "unexpected " + ex.message)
                ex.printStackTrace()
                assert(false)
            }
        }
    }

    private fun stopSynthesizing() {
        if (synthesizer != null) {
            synthesizer?.StopSpeakingAsync()
        }
        if (audioTrack != null) {
            synchronized (synchronizedObj) {
                stopped = true
            }
            audioTrack?.pause()
            audioTrack?.flush()
        }
    }

    private fun updateOutputMessage(text: String) {
        updateOutputMessage(text, false, true)
    }

    @Synchronized
    private fun updateOutputMessage(text: String, error: Boolean, append: Boolean) {
        this.runOnUiThread {
            if (append) {
                binding.outputMessage.append(text)
            } else {
                binding.outputMessage.text = text
            }
            if (error) {
                val spannableText = binding.outputMessage.text as Spannable
                spannableText.setSpan(ForegroundColorSpan(Color.RED),
                    spannableText.length - text.length,
                    spannableText.length,
                    0)
            }
        }
    }

    private fun clearOutputMessage() {
        updateOutputMessage("", false, false)
    }
}
