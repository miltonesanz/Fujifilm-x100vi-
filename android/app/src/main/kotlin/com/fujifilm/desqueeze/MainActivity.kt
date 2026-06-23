package com.fujifilm.desqueeze

import android.Manifest
import android.content.ContentValues
import android.content.Intent
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
import androidx.media3.common.MimeTypes
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
        for (uri in uris) {
            // Keep persistent read permission so the URI stays valid across async ops
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* not all providers grant persistable permissions */ }
            selectedUris.add(uri)
        }
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

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED)
            pickVideos.launch(arrayOf("video/*"))
        else
            requestPermission.launch(permission)
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
        // Probe dimensions on IO thread
        val (srcW, srcH) = withContext(Dispatchers.IO) { probeWidthHeight(uri) }
        if (srcW <= 0 || srcH <= 0) {
            uiLog("  ERROR: could not read video dimensions (unsupported format?)")
            return false
        }
        val newW = (srcW * squeeze / 2).toInt() * 2
        uiLog("  ${srcW}×${srcH}  →  desqueeze  →  ${newW}×${srcH}")

        val tmpOut = File(cacheDir, "desqueeze_${System.currentTimeMillis()}.mp4")
        try {
            // Transformer must run on Main; suspendCancellableCoroutine wraps the async callback
            val exportError = withContext(Dispatchers.Main) {
                runTransformer(uri, tmpOut.absolutePath, squeeze)
            }

            if (exportError != null) {
                uiLog("  ERROR: $exportError")
                return false
            }

            // Copy from cache to MediaStore (Movies/FujiDesqueeze/)
            val outputName = originalName.substringBeforeLast('.') + "_desqueezed.mp4"
            val destUri = withContext(Dispatchers.IO) { createOutputUri(outputName) }
            if (destUri == null) {
                uiLog("  ERROR: could not create output file in MediaStore")
                return false
            }
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(destUri)?.use { out ->
                    tmpOut.inputStream().use { it.copyTo(out) }
                }
            }
            uiLog("  Saved → Movies/FujiDesqueeze/$outputName")
            return true
        } catch (e: Exception) {
            uiLog("  ERROR: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            tmpOut.delete()
        }
    }

    // Returns null on success, or an error string on failure.
    // Must be called on the Main thread (Transformer requirement).
    private suspend fun runTransformer(
        inputUri: Uri,
        outputPath: String,
        squeeze: Float,
    ): String? = suspendCancellableCoroutine { cont ->

        val desqueeze = DesqueezeTransformation(squeeze)
        val effects = Effects(emptyList(), listOf(desqueeze))

        val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(effects)
            .build()

        val transformer = Transformer.Builder(this@MainActivity)
            // Explicitly transcode PCM S24 LE (Fujifilm) → AAC in the MP4 container
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            // Output as H.264 (universally compatible MP4)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .build()

        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                if (cont.isActive) cont.resume(null)
            }
            override fun onError(
                composition: Composition,
                result: ExportResult,
                exception: ExportException,
            ) {
                if (cont.isActive) cont.resume(
                    "ExportException ${exception.errorCode}: ${exception.message}"
                )
            }
        })

        cont.invokeOnCancellation { transformer.cancel() }

        try {
            transformer.start(editedItem, outputPath)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume("${e.javaClass.simpleName}: ${e.message}")
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
        } catch (e: Exception) {
            Pair(0, 0)
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
// configure() returns the wider output canvas; identity GL matrix causes the
// input texture to stretch (not crop) to fill the wider output = desqueeze.
@UnstableApi
class DesqueezeTransformation(private val squeezeFactor: Float) : GlMatrixTransformation {

    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        val newWidth = ((inputWidth * squeezeFactor).toInt() / 2) * 2
        return androidx.media3.common.util.Size(newWidth, inputHeight)
    }

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray {
        val m = FloatArray(16)
        android.opengl.Matrix.setIdentityM(m, 0)
        return m
    }
}
