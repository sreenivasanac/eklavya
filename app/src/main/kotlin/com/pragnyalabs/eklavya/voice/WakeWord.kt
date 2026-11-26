package com.pragnyalabs.eklavya.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Listens for the wake phrase on the phone itself, so no audio leaves it until the phrase is heard.
 *
 * The phrase is "Hey Drona" (assets/kws/keywords.txt, written as ARPAbet sounds, one line per way of saying
 * it). The model is sherpa-onnx's small open-vocabulary keyword spotter: it needs no training, and in tests
 * with synthesized voices it caught "Hey Drona" 16 times out of 16, also with heavy noise added, while it
 * missed "Hey Eklavya" every time. A phrase the model handles badly needs a trained model instead.
 */
class WakeWord(assets: AssetManager) {
    private val spotter = KeywordSpotter(
        assets,
        KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$DIR/encoder.int8.onnx",
                    decoder = "$DIR/decoder.onnx",
                    joiner = "$DIR/joiner.int8.onnx",
                ),
                tokens = "$DIR/tokens.txt",
                modelType = "zipformer2",
                numThreads = 1,
            ),
            keywordsFile = "$DIR/keywords.txt",
            keywordsScore = BOOST,
            keywordsThreshold = THRESHOLD,
        ),
    )
    private var stream: OnlineStream = spotter.createStream("")

    /** Feeds microphone samples (16 kHz mono); true when the wake phrase has just been heard. */
    fun heard(samples: ShortArray, count: Int): Boolean {
        val audio = FloatArray(count) { samples[it] / 32768f }
        stream.acceptWaveform(audio, SAMPLE_RATE)
        var found = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotEmpty()) {
                found = true
                spotter.reset(stream)
            }
        }
        return found
    }

    /** Forget what was heard so far; call when listening starts again after a conversation. */
    fun reset() {
        stream.release()
        stream = spotter.createStream("")
    }

    fun release() {
        stream.release()
        spotter.release()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val PHRASE = "Hey Drona"
        private const val DIR = "kws"

        /** Lower catches more and risks more false wake-ups; 0.2 caught every test clip with none false. */
        private const val THRESHOLD = 0.2f
        private const val BOOST = 1.5f
    }
}
