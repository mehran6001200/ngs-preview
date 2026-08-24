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


# 1) Close-voice capture: use Android speech/communications processing and NS.
patch_file(
    'app/src/main/java/com/ouor/rvcandroid/audio/PcmRecorder.kt',
    [
        ('val sampleRate: Int = 44_100,', 'val sampleRate: Int = 16_000,', True),
        ('import android.media.MediaRecorder\n',
         'import android.media.MediaRecorder\nimport android.media.audiofx.NoiseSuppressor\n', True),
        ('private var record: AudioRecord? = null\n',
         'private var record: AudioRecord? = null\n    private var noiseSuppressor: NoiseSuppressor? = null\n', True),
        ('MediaRecorder.AudioSource.MIC,', 'MediaRecorder.AudioSource.VOICE_COMMUNICATION,', True),
        ('rec.startRecording()\n\n        record = rec',
         'noiseSuppressor = if (NoiseSuppressor.isAvailable()) {\n'
         '            runCatching { NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true } }.getOrNull()\n'
         '        } else null\n'
         '        rec.startRecording()\n\n        record = rec', True),
        ('record = null\n        val file',
         'record = null\n        runCatching { noiseSuppressor?.release() }\n        noiseSuppressor = null\n        val file', True),
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
        // A26 v1.1 Compatibility ships a complete benchmark voice pack. Materialize assets once,
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
                    setF0UpKey(6)
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

# 5) Brand/version marker for this benchmark build.
main_path = ROOT / 'app/src/main/java/com/ouor/rvcandroid/MainActivity.kt'
main = main_path.read_text()
main = main.replace('RVC Android', 'A26 AI Female Voice v1.1')
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
gradle = gradle.replace('versionName = "1.0"', 'versionName = "1.1-compat"')
gradle_path.write_text(gradle)
print('patched app/build.gradle.kts')
