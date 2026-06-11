package com.fujifilm.desqueeze

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlMatrixTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.fujifilm.desqueeze.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedUris = mutableListOf<Uri>()
    private val squeeze = 1.33f

    // ── File picker ──────────────────────────────────────────────────────────
    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        selectedUris.clear()
        selectedUris.addAll(uris)
        binding.btnConvert.isEnabled = true
        val names = uris.joinToString("\n") { getFileName(it) }
        log("Selected ${uris.size} file(s):\n$names")
        binding.statusText.text = "${uris.size} file(s) ready"
    }

    // ── Permission ───────────────────────────────────────────────────────────
    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pickVideos.launch(arrayOf("video/*"))
        else log("Permission denied — please grant video access in Settings")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnPick.setOnClickListener { launchPicker() }
        binding.btnConvert.setOnClickListener { startConversion() }
        binding.btnConvert.isEnabled = false
        binding.subtitleText.text = "Media3 Transformer  ·  hardware-accelerated"
    }

    private fun launchPicker() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pickVideos.launch(arrayOf("video/*"))
        } else {
            requestPermission.launch(permission)
        }
    }

    // ── Conversion ───────────────────────────────────────────────────────────
    private fun startConversion() {
        if (selectedUris.isEmpty()) return
        binding.btnPick.isEnabled = false
        binding.btnConvert.isEnabled = false
        binding.progressBar.progress = 0
        binding.logText.text = ""

        lifecycleScope.launch {
            val total = selectedUris.size
            var done = 0
            var failed = 0

            for (uri in selectedUris) {
                val name = getFileName(uri)
                log("\n── $name ──")
                val ok = convertFile(uri, name)
                if (ok) done++ else failed++
                binding.progressBar.progress = ((done + failed) * 100 / total)
            }

            val summary = "Done: $done succeeded" + if (failed > 0) ", $failed failed" else ""
            binding.statusText.text = summary
            log("\n$summary")
            binding.btnPick.isEnabled = true
            binding.btnConvert.isEnabled = selectedUris.isNotEmpty()
        }
    }

    private suspend fun convertFile(uri: Uri, originalName: String): Boolean {
        // 1. Probe source dimensions
        val (srcW, srcH) = probeWidthHeight(uri)
        if (srcW <= 0 || srcH <= 0) {
            uiLog("  ERROR: could not read video dimensions")
            return false
        }
        val newW = (srcW * squeeze / 2).toInt() * 2
        uiLog("  ${srcW}×${srcH}  →  desqueeze  →  ${newW}×${srcH}")

        // 2. Temp output file in cache (Transformer needs a file path)
        val tmpOut = File(cacheDir, "desqueeze_${System.currentTimeMillis()}.mp4")

        return try {
            // 3. Run transformer on a background thread
            val ok = withContext(Dispatchers.Main) {
                runTransformer(uri, tmpOut.absolutePath, squeeze)
            }
            if (!ok) return false

            // 4. Copy temp file into MediaStore (Movies/FujiDesqueeze/)
            withContext(Dispatchers.IO) {
                val outputName = originalName.substringBeforeLast(".") + "_desqueezed.mp4"
                val destUri = createOutputUri(outputName)
                if (destUri == null) {
                    uiLog("  ERROR: could not create output in MediaStore")
                    return@withContext false
                }
                contentResolver.openOutputStream(destUri)?.use { out ->
                    tmpOut.inputStream().use { it.copyTo(out) }
                }
                uiLog("  Saved → Movies/FujiDesqueeze/$outputName")
                true
            }
        } finally {
            tmpOut.delete()
        }
    }

    // Transformer must run on the main thread; we wrap it in a coroutine suspending call.
    private suspend fun runTransformer(inputUri: Uri, outputPath: String, squeeze: Float): Boolean =
        suspendCancellableCoroutine { cont ->
            val desqueeze = DesqueezeTransformation(squeeze)
            val effects = Effects(emptyList(), listOf(desqueeze))

            val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                .setEffects(effects)
                .build()

            val transformer = Transformer.Builder(this)
                .build()

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onError(
                    composition: Composition,
                    result: ExportResult,
                    exception: ExportException,
                ) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })

            cont.invokeOnCancellation { transformer.cancel() }

            try {
                transformer.start(editedItem, outputPath)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun probeWidthHeight(uri: Uri): Pair<Int, Int> {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val w = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            Pair(w, h)
        } finally {
            retriever.release()
        }
    }

    private fun createOutputUri(filename: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/FujiDesqueeze")
        }
        return contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "unknown"
    }

    private suspend fun uiLog(msg: String) = withContext(Dispatchers.Main) { log(msg) }

    private fun log(msg: String) {
        val current = binding.logText.text.toString()
        binding.logText.text = if (current.isEmpty()) msg else "$current\n$msg"
        binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}

// ── Desqueeze GL transformation ───────────────────────────────────────────────
// configure() returns the wider output canvas; identity matrix causes the
// input texture to be stretched (not cropped) to fill the wider output.
@UnstableApi
class DesqueezeTransformation(private val squeezeFactor: Float) : GlMatrixTransformation {

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        val newWidth = ((inputWidth * squeezeFactor).toInt() / 2) * 2
        return androidx.media3.common.util.Size(newWidth, inputHeight)
    }

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        val matrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(matrix, 0)
        return matrix
    }
}
