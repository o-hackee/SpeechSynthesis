//
// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE.md file in the project root for full license information.
//
package com.microsoft.cognitiveservices.speech.samples.speechsynthesis

import android.Manifest.permission
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.microsoft.cognitiveservices.speech.Connection
import com.microsoft.cognitiveservices.speech.ConnectionEventArgs
import com.microsoft.cognitiveservices.speech.PropertyId
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechSynthesisCancellationDetails
import com.microsoft.cognitiveservices.speech.SpeechSynthesisEventArgs
import com.microsoft.cognitiveservices.speech.SpeechSynthesisResult
import com.microsoft.cognitiveservices.speech.SpeechSynthesisWordBoundaryEventArgs
import com.microsoft.cognitiveservices.speech.SpeechSynthesizer
import com.microsoft.cognitiveservices.speech.samples.speechsynthesis.databinding.ActivityMainBinding
import java.util.concurrent.Future

class MainActivity : AppCompatActivity() {

    companion object {
        const val synthesisVoice = "ru-RU-DmitryNeural"
    }

    private var speechConfig: SpeechConfig? = null
    private var synthesizer: SpeechSynthesizer? = null
    private var connection: Connection? = null
    private var future: Future<SpeechSynthesisResult>? = null

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Note: we need to request the permissions
        val requestCode = 5 // Unique code for the permission request
        ActivityCompat.requestPermissions(this@MainActivity, arrayOf(permission.INTERNET), requestCode)

        binding.outputMessage.movementMethod = ScrollingMovementMethod()
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

        if (future?.isDone == false)
            future?.cancel(true)
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
        // Set voice name.
        speechConfig?.speechSynthesisVoiceName = synthesisVoice
        // use the default speaker on the system for audio output
        synthesizer = SpeechSynthesizer(speechConfig)
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

        try {
            future = synthesizer!!.SpeakTextAsync(binding.speakText.text.toString())
        } catch (ex: Exception) {
            Log.e("Speech Synthesis Demo", "unexpected " + ex.message)
            ex.printStackTrace()
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
