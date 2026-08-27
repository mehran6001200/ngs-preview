from pathlib import Path

# Apply the proven v1.2 Android/model-loading fixes first.
exec(Path('A26_FemaleVoice/patch_rvc_android_v1.py').read_text(), globals())

ROOT = Path('rvc')


def must_replace(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f'v1.3 patch pattern not found: {label}')
    return text.replace(old, new)


# ---------------------------------------------------------------------------
# v1.3: preload a compact retrieval ONNX generated from p249's real FAISS IVF
# index. This lets Android use RVC-style feature retrieval without shipping or
# embedding FAISS native libraries.
# ---------------------------------------------------------------------------
vm_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/ui/ConversionViewModel.kt'
vm = vm_path.read_text()
vm = must_replace(
    vm,
    '    private var synthMetadata: ModelMetadata? = null\n',
    '    private var synthMetadata: ModelMetadata? = null\n    private var retrievalSession: OrtSession? = null\n',
    'retrieval field',
)
vm = must_replace(
    vm,
    '                val rmvpe = materialize("rmvpe_20231006.onnx")\n                withContext(Dispatchers.Main) {',
    '                val rmvpe = materialize("rmvpe_20231006.onnx")\n'
    '                val retrieval = materialize("retrieval_p249.onnx")\n'
    '                runCatching { retrievalSession?.close() }\n'
    '                retrievalSession = OrtRuntime.openSession(retrieval)\n'
    '                withContext(Dispatchers.Main) {',
    'preload retrieval',
)
vm = must_replace(
    vm,
    '                    setF0UpKey(9)\n',
    '                    setF0UpKey(8)\n',
    'default pitch +8',
)
vm = must_replace(
    vm,
    '        val rmvpe = sessions[Slot.RMVPE]\n        val meta = synthMetadata ?: error("synth metadata missing")',
    '        val rmvpe = sessions[Slot.RMVPE]\n'
    '        val retrieval = retrievalSession ?: error("retrieval index model not loaded")\n'
    '        val meta = synthMetadata ?: error("synth metadata missing")',
    'obtain retrieval',
)
vm = must_replace(
    vm,
    '            RvcPipelineFactory.assemble(synth, meta, hubert, rmvpe)\n',
    '            RvcPipelineFactory.assemble(synth, meta, hubert, rmvpe, retrieval)\n',
    'assemble retrieval',
)
vm = must_replace(
    vm,
    '        Slot.entries.forEach { closeSession(it) }\n        invalidatePipeline()\n',
    '        Slot.entries.forEach { closeSession(it) }\n'
    '        runCatching { retrievalSession?.close() }\n'
    '        retrievalSession = null\n'
    '        invalidatePipeline()\n',
    'close retrieval',
)
vm_path.write_text(vm)
print('patched ConversionViewModel.kt for retrieval')


# ---------------------------------------------------------------------------
# Retrieval session wrapper: input/output stay Float32 and ORT handles the
# expensive matrix math natively. The ONNX model performs top-8 weighted
# nearest-centroid retrieval from p249's real RVC index.
# ---------------------------------------------------------------------------
retrieval_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/inference/RetrievalBlender.kt'
retrieval_path.write_text(r'''package com.ouor.rvcandroid.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log

private const val TAG = "Rvc.Retrieval"

class RetrievalBlender(private val session: OrtSession) {
    fun retrieve(feats: FloatArray, frames: Int, channels: Int): FloatArray {
        require(channels == 768) { "retrieval model expects 768 channels, got $channels" }
        val t0 = System.currentTimeMillis()
        val env = OrtRuntime.env
        env.floatTensor(feats, longArrayOf(1L, frames.toLong(), channels.toLong())).use { x ->
            session.run(mapOf("feats" to x), setOf("retrieved")).use { result ->
                val tensor = result.iterator().next().value as OnnxTensor
                val shape = (tensor.info as TensorInfo).shape
                require(shape.size == 3 && shape[0] == 1L && shape[1] == frames.toLong() && shape[2] == channels.toLong()) {
                    "bad retrieval output shape ${shape.contentToString()}"
                }
                val out = tensor.copyFloats()
                Log.i(TAG, "retrieve: $frames x $channels in ${System.currentTimeMillis() - t0}ms")
                return out
            }
        }
    }
}
''')
print('created RetrievalBlender.kt')


# ---------------------------------------------------------------------------
# Replace the simplified Android RVC pipeline with a closer match to official
# RVC inference:
#   * p249 retrieval blend (index_rate=0.55)
#   * protect=0.33 for unvoiced consonants/breath
#   * source/output RMS envelope blend (rms_mix_rate=0.25)
# ---------------------------------------------------------------------------
pipeline_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/inference/RvcPipeline.kt'
pipeline_path.write_text(r'''package com.ouor.rvcandroid.inference

import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.Closeable
import kotlin.math.pow
import kotlin.math.sqrt

private const val TAG = "Rvc.Pipe"
private const val INDEX_RATE = 0.55f
private const val PROTECT = 0.33f
private const val RMS_MIX_RATE = 0.25f

class RvcPipeline(
    val metadata: ModelMetadata,
    private val embedder: HubertEmbedder,
    private val pitchExtractor: RmvpePitchExtractor?,
    private val synthesizer: RvcSynthesizer,
    private val retriever: RetrievalBlender?,
) : Closeable {

    val outputSampleRate: Int get() = metadata.samplingRate

    fun convert(
        audio16k: FloatArray,
        f0UpKey: Int = 0,
        speakerId: Long = 0L,
        onProgress: (Float) -> Unit = {},
    ): FloatArray {
        val t0 = System.currentTimeMillis()
        Log.i(
            TAG,
            "convert: input=${audio16k.size} samples (${audio16k.size / 16}ms), " +
                "f0UpKey=$f0UpKey sid=$speakerId target=${metadata.samplingRate}Hz " +
                "indexRate=$INDEX_RATE protect=$PROTECT rmsMix=$RMS_MIX_RATE",
        )
        onProgress(0f)

        val emb = embedder.extract(audio16k)
        val original = emb.feats
        val retrieved = retriever?.retrieve(original, emb.frames, emb.channels)
        val base = if (retrieved != null && retrieved.size == original.size) {
            FloatArray(original.size) { i -> original[i] * (1f - INDEX_RATE) + retrieved[i] * INDEX_RATE }
        } else {
            original.copyOf()
        }
        onProgress(0.4f)

        val pitch = if (metadata.f0) {
            requireNotNull(pitchExtractor) { "f0 model requires pitch extractor" }
            pitchExtractor.extract(audio16k, f0UpKey)
        } else null
        onProgress(0.6f)

        val frames2x = emb.frames * 2
        val feats2x = upsample2xNearest(base, emb.frames, emb.channels)
        val original2x = upsample2xNearest(original, emb.frames, emb.channels)

        val targetT = if (pitch != null) minOf(frames2x, pitch.pitchf.size) else frames2x
        val feats = if (frames2x == targetT) feats2x else feats2x.copyOf(targetT * emb.channels)
        val originalAligned = if (frames2x == targetT) original2x else original2x.copyOf(targetT * emb.channels)
        val coarse = pitch?.pitchCoarse?.copyOf(targetT)
        val pitchf = pitch?.pitchf?.copyOf(targetT)

        // Official RVC protect behavior: voiced frames use the retrieved feature
        // entirely; unvoiced frames retain most of the original HuBERT feature.
        if (pitchf != null && PROTECT < 0.5f) {
            for (t in 0 until targetT) {
                val mask = if (pitchf[t] > 0f) 1f else PROTECT
                if (mask < 1f) {
                    val start = t * emb.channels
                    val end = start + emb.channels
                    for (i in start until end) {
                        feats[i] = feats[i] * mask + originalAligned[i] * (1f - mask)
                    }
                }
            }
        }

        Log.d(TAG, "aligned T=$targetT feats=${feats.size} pitch=${coarse?.size}")
        onProgress(0.7f)

        val synthesized = synthesizer.infer(
            feats = feats,
            framesT = targetT,
            channels = emb.channels,
            pitch = coarse,
            pitchf = pitchf,
            speakerId = speakerId,
        )

        // Official RVC uses a slow RMS envelope sampled every 0.5s. Mimic that
        // behavior to preserve the natural dynamics of the original recording.
        val audio = if (RMS_MIX_RATE < 1f) {
            changeRms(audio16k, 16_000, synthesized, metadata.samplingRate, RMS_MIX_RATE)
        } else synthesized
        onProgress(1f)

        val elapsed = System.currentTimeMillis() - t0
        Log.i(TAG, "convert: done audio=${audio.size} @ ${metadata.samplingRate}Hz in ${elapsed}ms")
        return audio
    }

    override fun close() {
        Log.d(TAG, "close")
    }

    private fun upsample2xNearest(feats: FloatArray, frames: Int, channels: Int): FloatArray {
        val out = FloatArray(frames * 2 * channels)
        for (t in 0 until frames) {
            val src = t * channels
            val dst = t * 2 * channels
            System.arraycopy(feats, src, out, dst, channels)
            System.arraycopy(feats, src, out, dst + channels, channels)
        }
        return out
    }

    private fun changeRms(
        src: FloatArray,
        srcRate: Int,
        out: FloatArray,
        outRate: Int,
        rate: Float,
    ): FloatArray {
        if (src.isEmpty() || out.isEmpty()) return out
        val srcEnv = rmsPoints(src, srcRate)
        val outEnv = rmsPoints(out, outRate)
        if (srcEnv.isEmpty() || outEnv.isEmpty()) return out
        val result = out.copyOf()
        val exponent = (1f - rate).toDouble()
        for (i in result.indices) {
            val p = if (result.size <= 1) 0.0 else i.toDouble() / (result.size - 1).toDouble()
            val a = interp(srcEnv, p).coerceAtLeast(1e-6f)
            val b = interp(outEnv, p).coerceAtLeast(1e-6f)
            var gain = (a.toDouble() / b.toDouble()).pow(exponent).toFloat()
            // Avoid pathological gain explosions on silence boundaries.
            gain = gain.coerceIn(0.25f, 4.0f)
            result[i] *= gain
        }
        return result
    }

    private fun rmsPoints(audio: FloatArray, sr: Int): FloatArray {
        val frame = sr.coerceAtLeast(1)       // 1 second, matches sr//2*2
        val hop = (sr / 2).coerceAtLeast(1)   // one point every 0.5 second
        if (audio.isEmpty()) return FloatArray(0)
        val count = ((audio.size + hop - 1) / hop).coerceAtLeast(1)
        val out = FloatArray(count)
        for (n in 0 until count) {
            val center = n * hop
            val start = (center - frame / 2).coerceAtLeast(0)
            val end = (center + frame / 2).coerceAtMost(audio.size)
            if (end <= start) {
                out[n] = 1e-6f
                continue
            }
            var sum = 0.0
            for (i in start until end) {
                val v = audio[i].toDouble()
                sum += v * v
            }
            out[n] = sqrt(sum / (end - start).toDouble()).toFloat().coerceAtLeast(1e-6f)
        }
        return out
    }

    private fun interp(points: FloatArray, p: Double): Float {
        if (points.size == 1) return points[0]
        val x = p.coerceIn(0.0, 1.0) * (points.size - 1)
        val i = x.toInt().coerceIn(0, points.lastIndex)
        val j = (i + 1).coerceAtMost(points.lastIndex)
        val f = (x - i).toFloat()
        return points[i] + (points[j] - points[i]) * f
    }
}

object RvcPipelineFactory {
    fun assemble(
        synthSession: OrtSession,
        synthMetadata: ModelMetadata,
        hubertSession: OrtSession,
        rmvpeSession: OrtSession?,
        retrievalSession: OrtSession? = null,
    ): RvcPipeline {
        if (synthMetadata.f0) {
            requireNotNull(rmvpeSession) { "f0 model selected but no rmvpe session provided" }
        }
        val hubertOutput = chooseHubertOutput(synthMetadata)
        Log.i(TAG, "assemble: hubertOutput=$hubertOutput f0=${synthMetadata.f0} retrieval=${retrievalSession != null}")
        return RvcPipeline(
            metadata = synthMetadata,
            embedder = HubertEmbedder(hubertSession, hubertOutput),
            pitchExtractor = rmvpeSession?.let { RmvpePitchExtractor(it) },
            synthesizer = RvcSynthesizer(synthSession, synthMetadata.f0),
            retriever = retrievalSession?.let { RetrievalBlender(it) },
        )
    }

    fun create(
        ctx: Context,
        modelUri: Uri,
        hubertUri: Uri,
        rmvpeUri: Uri?,
    ): RvcPipeline {
        var synth: OrtSession? = null
        var hubert: OrtSession? = null
        var rmvpe: OrtSession? = null
        try {
            synth = OrtRuntime.openSession(ctx, modelUri)
            val metadata = ModelMetadata.fromSession(synth)
                ?: error("synthesizer has no embedded metadata; export it via voice-changer")
            hubert = OrtRuntime.openSession(ctx, hubertUri)
            if (metadata.f0) {
                requireNotNull(rmvpeUri) { "f0 model selected but no rmvpe uri provided" }
                rmvpe = OrtRuntime.openSession(ctx, rmvpeUri)
            }
            return assemble(synth, metadata, hubert, rmvpe, null)
        } catch (t: Throwable) {
            runCatching { synth?.close() }
            runCatching { hubert?.close() }
            runCatching { rmvpe?.close() }
            throw t
        }
    }

    private fun chooseHubertOutput(meta: ModelMetadata): String = when {
        meta.embOutputLayer == 12 && !meta.useFinalProj -> "unit12"
        meta.embOutputLayer == 9 && meta.useFinalProj -> "units9"
        meta.embOutputLayer == 12 && meta.useFinalProj -> "unit12s"
        else -> error(
            "unsupported embedder config: layer=${meta.embOutputLayer}, finalProj=${meta.useFinalProj}",
        )
    }
}
''')
print('replaced RvcPipeline.kt with indexed/protected/RMS pipeline')


# Brand/version marker after the base v1.2 patch.
main_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/MainActivity.kt'
main = main_path.read_text().replace('A26 AI Female Voice v1.2', 'A26 AI Female Voice v1.3')
main_path.write_text(main)

gradle_path = ROOT / 'app/build.gradle.kts'
gradle = gradle_path.read_text().replace('versionName = "1.2-natural"', 'versionName = "1.3-indexed"')
gradle_path.write_text(gradle)
print('branded v1.3-indexed')
