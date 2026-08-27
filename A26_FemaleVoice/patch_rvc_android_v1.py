from pathlib import Path

ROOT = Path('rvc')


def patch_file(rel, transforms):
    p = ROOT / rel
    s = p.read_text()
    original = s
    for old, new, required in transforms:
        if old not in s:
            if required:
                raise RuntimeError(f'pattern not found in {rel}: {old[:100]!r}')
            continue
        s = s.replace(old, new)
    if s == original:
        raise RuntimeError(f'no changes made to {rel}')
    p.write_text(s)
    print('patched', rel)


# 1) Natural capture: full-band speech input, no telephony DSP/NoiseSuppressor.
patch_file(
    'app/src/main/java/com/ouor/rvcandroid/audio/PcmRecorder.kt',
    [
        ('val sampleRate: Int = 44_100,', 'val sampleRate: Int = 48_000,', True),
        ('MediaRecorder.AudioSource.MIC,', 'MediaRecorder.AudioSource.VOICE_RECOGNITION,', True),
    ],
)

# 2) Allow quantized ContentVec model whose schema is input_values -> hidden_states.
hubert_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/inference/HubertEmbedder.kt'
hubert = hubert_path.read_text()
hubert = hubert.replace(
'''    init {
        require(outputName in session.outputInfo.keys) {
            "hubert ONNX has no '$outputName' output (available: ${session.outputInfo.keys})"
        }
        Log.i(TAG, "init: outputName=$outputName")
    }
''',
'''    private val actualOutputName: String = when {
        outputName in session.outputInfo.keys -> outputName
        "hidden_states" in session.outputInfo.keys -> "hidden_states"
        else -> session.outputInfo.keys.firstOrNull()
            ?: error("HuBERT/ContentVec model exposes no outputs")
    }

    private val actualInputName: String = when {
        "audio" in session.inputInfo.keys -> "audio"
        "input_values" in session.inputInfo.keys -> "input_values"
        else -> session.inputInfo.keys.firstOrNull()
            ?: error("HuBERT/ContentVec model exposes no inputs")
    }

    init {
        Log.i(TAG, "init: requestedOutput=$outputName actualOutput=$actualOutputName input=$actualInputName")
    }
''')
hubert = hubert.replace(
'''        env.floatTensor(audio16k, longArrayOf(1L, audio16k.size.toLong())).use { audio ->
            session.run(mapOf("audio" to audio), setOf(outputName)).use { result ->
''',
'''        env.floatTensor(audio16k, longArrayOf(1L, audio16k.size.toLong())).use { audio ->
            val inputs = mutableMapOf<String, OnnxTensor>(actualInputName to audio)
            var mask: OnnxTensor? = null
            try {
                if ("attention_mask" in session.inputInfo.keys) {
                    mask = env.longTensor(LongArray(audio16k.size) { 1L }, longArrayOf(1L, audio16k.size.toLong()))
                    inputs["attention_mask"] = mask
                }
                session.run(inputs, setOf(actualOutputName)).use { result ->
''')
hubert = hubert.replace(
'''                    "extract: audio=${audio16k.size} → $outputName[1, $frames, $channels] in ${elapsed}ms",
                )
                return EmbeddingData(feats, frames, channels)
            }
        }
''',
'''                    "extract: audio=${audio16k.size} → $actualOutputName[1, $frames, $channels] in ${elapsed}ms",
                )
                return EmbeddingData(feats, frames, channels)
                }
            } finally {
                runCatching { mask?.close() }
            }
        }
''')
hubert_path.write_text(hubert)
print('patched HubertEmbedder.kt')

# 3) file:// model URIs should be mmap'd directly instead of copied again to cache.
patch_file(
    'app/src/main/java/com/ouor/rvcandroid/inference/OrtRuntime.kt',
    [
        ('    fun openSession(ctx: Context, uri: Uri): OrtSession {\n        return openSession(ensureCachedFile(ctx, uri))\n    }',
         '    fun openSession(ctx: Context, uri: Uri): OrtSession {\n'
         '        if (uri.scheme == "file") {\n'
         '            val p = requireNotNull(uri.path) { "file URI has no path: $uri" }\n'
         '            return openSession(File(p))\n'
         '        }\n'
         '        return openSession(ensureCachedFile(ctx, uri))\n'
         '    }', True),
    ],
)

# 4) Preload bundled voice pack automatically. Keep manual pickers as fallback/debug.
vm_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/ui/ConversionViewModel.kt'
vm = vm_path.read_text()
vm = vm.replace('import java.io.File\n', 'import java.io.File\nimport java.io.FileOutputStream\n')
vm = vm.replace(
'''    init {
        // Surface whatever's already in the LRU on first compose so the
        // history card is populated across process restarts.
        refreshHistory()
    }
''',
'''    init {
        // A26 v1.2 Compatibility ships a complete benchmark voice pack. Materialize assets once,
        // then load all three ONNX sessions automatically. Manual pickers remain
        // available only as a fallback/debug path.
        preloadBundledVoicePack()
        refreshHistory()
    }

    private fun preloadBundledVoicePack() {
        val ctx: Context = getApplication()
        val dir = File(ctx.filesDir, "a26-ai-models").apply { mkdirs() }
        fun materialize(assetName: String): File {
            val dst = File(dir, assetName)
            if (!dst.exists() || dst.length() == 0L) {
                val tmp = File(dir, "$assetName.tmp")
                ctx.assets.open("a26/$assetName").use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output, 1024 * 1024) }
                }
                if (dst.exists()) dst.delete()
                check(tmp.renameTo(dst)) { "Could not install $assetName" }
            }
            return dst
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val synth = materialize("female.onnx")
                val hubert = materialize("contentvec_768l12.onnx")
                val rmvpe = materialize("rmvpe_20231006.onnx")
                withContext(Dispatchers.Main) {
                    setModel(synth.toUri())
                    setHubert(hubert.toUri())
                    setRmvpe(rmvpe.toUri())
                    setF0UpKey(9)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "bundled voice pack install failed", t)
                _state.update { it.copy(message = "Voice pack install failed: ${t.message}") }
            }
        }
    }
''')
vm = vm.replace(
'''private fun queryDisplayName(ctx: Context, uri: Uri): String {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
    return uri.lastPathSegment ?: "(unknown)"
}
''',
'''private fun queryDisplayName(ctx: Context, uri: Uri): String {
    if (uri.scheme == "file") return File(uri.path ?: "").name.ifEmpty { "(model)" }
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) return c.getString(0) }
    return uri.lastPathSegment ?: "(unknown)"
}
''')
vm_path.write_text(vm)
print('patched ConversionViewModel.kt')


# 4b) High-quality anti-alias resampler. The original linear interpolator aliases
# badly when 44.1/48 kHz recordings are reduced to 16 kHz and can sound metallic.
resamp_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/audio/Resampler.kt'
resamp_path.write_text(r'''package com.ouor.rvcandroid.audio

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val TAG = "Rvc.Resamp"

object Resampler {
    private const val HALF_TAPS = 12

    fun resample(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        require(srcRate > 0 && dstRate > 0) { "invalid sample rate" }
        if (srcRate == dstRate || input.isEmpty()) return input

        val outLen = ((input.size.toLong() * dstRate) / srcRate).toInt()
        val ratio = srcRate.toDouble() / dstRate
        val cutoff = minOf(1.0, dstRate.toDouble() / srcRate) * 0.94
        val out = FloatArray(outLen)
        val last = input.lastIndex

        Log.d(TAG, "sinc resample: ${srcRate}Hz -> ${dstRate}Hz, ${input.size} -> $outLen")

        for (i in 0 until outLen) {
            val pos = i * ratio
            val center = floor(pos).toInt()
            var sum = 0.0
            var weightSum = 0.0
            for (k in -HALF_TAPS..HALF_TAPS) {
                val idx = center + k
                if (idx < 0 || idx > last) continue
                val x = (idx - pos) * cutoff
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                val wx = (idx - pos) / (HALF_TAPS + 1.0)
                val window = if (abs(wx) <= 1.0) 0.5 + 0.5 * cos(PI * wx) else 0.0
                val w = sinc * cutoff * window
                sum += input[idx] * w
                weightSum += w
            }
            out[i] = if (abs(weightSum) > 1e-12) (sum / weightSum).toFloat() else 0f
        }
        return out
    }
}
''')
print('patched Resampler.kt with anti-alias sinc')

# 4c) Stabilise RMVPE F0 before transposition. Median smoothing suppresses
# single-frame pitch jitter that is heard as tremolo/robotic flutter.
rmvpe_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/inference/RmvpePitchExtractor.kt'
rm = rmvpe_path.read_text()
rm = rm.replace(
    'val shifted = if (f0UpKey == 0) raw else shift(raw, f0UpKey)',
    '''val stable = medianSmoothVoiced(raw)
                    val shifted = if (f0UpKey == 0) stable else shift(stable, f0UpKey)'''
)
needle = '''    private fun shift(pitchf: FloatArray, semitones: Int): FloatArray {
        val factor = 2.0.pow(semitones / 12.0).toFloat()
        return FloatArray(pitchf.size) { pitchf[it] * factor }
    }
'''
replacement = '''    private fun medianSmoothVoiced(src: FloatArray): FloatArray {
        if (src.size < 3) return src.copyOf()
        val out = FloatArray(src.size)
        for (i in src.indices) {
            if (src[i] <= 0f) {
                out[i] = 0f
                continue
            }
            val vals = ArrayList<Float>(5)
            val a = (i - 2).coerceAtLeast(0)
            val b = (i + 2).coerceAtMost(src.lastIndex)
            for (j in a..b) if (src[j] > 0f) vals.add(src[j])
            vals.sort()
            out[i] = if (vals.isEmpty()) src[i] else vals[vals.size / 2]
        }
        return out
    }

    private fun shift(pitchf: FloatArray, semitones: Int): FloatArray {
        val factor = 2.0.pow(semitones / 12.0).toFloat()
        return FloatArray(pitchf.size) { pitchf[it] * factor }
    }
'''
if needle not in rm:
    raise RuntimeError('RMVPE shift block not found')
rm = rm.replace(needle, replacement)
rmvpe_path.write_text(rm)
print('patched RMVPE pitch smoothing')


# 5) Brand/version marker for this benchmark build.
main_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/MainActivity.kt'
main = main_path.read_text()
main = main.replace('RVC Android', 'A26 AI Female Voice v1.2')
main_path.write_text(main)
print('patched MainActivity.kt')

# 6) Keep giant ONNX files uncompressed in APK; avoids pointless compression and speeds install/extract.
gradle_path = ROOT / 'app/build.gradle.kts'
gradle = gradle_path.read_text()
needle = '''    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
'''
replacement = '''    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        noCompress += listOf("onnx")
    }
'''
if needle not in gradle:
    raise RuntimeError('build.gradle.kts packaging block not found')
gradle = gradle.replace(needle, replacement)
gradle = gradle.replace('versionName = "1.0"', 'versionName = "1.2-natural"')
gradle_path.write_text(gradle)
print('patched app/build.gradle.kts')
