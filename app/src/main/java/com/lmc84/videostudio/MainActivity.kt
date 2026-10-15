@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lmc84.videostudio

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.setPadding
import androidx.media3.common.MediaItem
import androidx.media3.common.Effect
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var preview: PlayerView
    private var player: ExoPlayer? = null
    private var inputUri: Uri? = null
    private var xmlUri: Uri? = null
    private var transformer: Transformer? = null
    private var exporting = false
    private var compareOriginal = false

    private data class Knob(val label: String, var value: Float, val min: Float, val max: Float)
    private val knobs = linkedMapOf(
        "texture" to Knob("Micro-Texture Pop", .50f, 0f, 1.5f),
        "sharpen" to Knob("CAS Smart Sharpening", .40f, 0f, 1.5f),
        "clarity" to Knob("HDR Clarity", .30f, 0f, 1f),
        "contrast" to Knob("HDR Contrast", 1f, .6f, 1.8f),
        "saturate" to Knob("LMC Saturation", 1f, 0f, 2.2f),
        "brightness" to Knob("Exposure / Shadow", 0f, -.3f, .3f),
        "warmth" to Knob("Leica Golden Warmth", 0f, -.3f, .4f),
        "vignette" to Knob("LMC Lens Vignette", .15f, 0f, .7f)
    )
    private var upscale = 1.5f
    private var bitrate = 25_000_000
    private val seekBars = mutableMapOf<String, SeekBar>()
    private val sliderLabels = mutableMapOf<String, TextView>()

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            inputUri = uri
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val p = player ?: ExoPlayer.Builder(this).build().also { player = it; preview.player = it }
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.playWhenReady = false
            refreshPreviewEffects()
            status.text = "Video loaded. Native preview ready; choose a preset or adjust controls."
            updateDetails()
        }
    }
    private val xmlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            xmlUri = uri
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            readXml(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(7, 11, 20)
        window.navigationBarColor = Color.rgb(7, 11, 20)
        buildUi()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14))
            setBackgroundColor(Color.rgb(7, 11, 20))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("LMC 8.4 VIDEO STUDIO", 22, Color.WHITE, true))
        root.addView(text("Native Android • Hardware-accelerated export", 12, Color.LTGRAY))
        root.addView(space(10))

        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickRow.addView(button("📁 Select Video") { videoPicker.launch(arrayOf("video/*")) }, weight())
        pickRow.addView(button("⚙ Load LMC XML") { xmlPicker.launch(arrayOf("text/*", "application/xml", "*/*")) }, weight())
        root.addView(pickRow)

        preview = PlayerView(this).apply {
            useController = true
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(10); bottomMargin = dp(8) }
        }
        root.addView(preview)
        player = ExoPlayer.Builder(this).build().also { preview.player = it }

        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        transport.addView(button("▶ Play/Pause") {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }, weight())
        transport.addView(button("👁 Hold Original") {
            compareOriginal = !compareOriginal
            refreshPreviewEffects()
            status.text = if (compareOriginal) "Showing original preview. Export settings are unchanged." else "Showing graded preview."
        }, weight())
        transport.addView(button("↺ Reset") { resetAll() }, weight())
        root.addView(transport)

        root.addView(section("QUICK PRESETS"))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        presets.addView(presetRow(
            "🔥 Leica 4K Crisp" to { applyPreset(1.28f, 1.50f, .01f, .10f, .65f, .45f) },
            "💎 Ultra Retexture" to { applyPreset(1.18f, 1.30f, .02f, .04f, .85f, .60f) }
        ))
        presets.addView(presetRow(
            "🌆 HDR+ Detail Pop" to { applyPreset(1.40f, 1.25f, -.03f, 0f, .50f, .55f) },
            "🎬 Moody Cinema" to { applyPreset(1.25f, .88f, -.05f, .14f, .40f, .35f) }
        ))
        root.addView(presets)

        root.addView(section("AI-SHADER RETEXTURE & CAS"))
        knobs.forEach { (key, knob) -> addSlider(key, knob) }

        root.addView(section("SUPER-RESOLUTION & EXPORT"))
        val resolution = Spinner(this)
        resolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("1.0× Original resolution", "1.5× Super HD", "2.0× Upscale"))
        resolution.setSelection(1)
        resolution.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                upscale = when (position) { 0 -> 1f; 1 -> 1.5f; else -> 2f }
                updateDetails()
            }
        }
        root.addView(resolution)
        val rates = Spinner(this)
        rates.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("14 Mbps • Fast Smooth", "25 Mbps • Crystal HD", "40 Mbps • Extreme"))
        rates.setSelection(1)
        rates.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                bitrate = when (position) { 0 -> 14_000_000; 1 -> 25_000_000; else -> 40_000_000 }
            }
        }
        root.addView(rates)

        val export = button("🚀 Export HD MP4") { startExport() }
        root.addView(export)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(10) })
        status = text("Choose a video to begin.", 13, Color.rgb(255, 190, 70))
        status.setPadding(dp(8))
        root.addView(status)
        root.addView(text("4 GB RAM tip: use 1× or 1.5× and 14–25 Mbps for the most reliable export.", 12, Color.LTGRAY))
    }

    private fun addSlider(key: String, knob: Knob) {
        val label = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE) }
        val seek = SeekBar(this).apply { max = 1000; progress = toProgress(knob) }
        seekBars[key] = seek
        sliderLabels[key] = label
        fun updateLabel() { label.text = "${knob.label}: ${String.format(Locale.US, "%.2f", knob.value)}" }
        updateLabel()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    knob.value = knob.min + (knob.max - knob.min) * p / 1000f
                    updateLabel()
                    refreshPreviewEffects()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(label)
        root.addView(seek)
    }

    private fun toProgress(k: Knob) = (((k.value - k.min) / (k.max - k.min)) * 1000).toInt().coerceIn(0, 1000)

    private fun startExport() {
        val source = inputUri
        if (source == null) {
            status.text = "Select a video first."
            return
        }
        if (exporting) return
        exporting = true
        progress.progress = 0
        status.text = "Preparing hardware-accelerated MP4 export…"
        player?.pause()

        val outDir = getExternalFilesDir("exports") ?: filesDir
        if (!outDir.exists()) outDir.mkdirs()
        val outFile = File(outDir, "LMC84_${System.currentTimeMillis()}.mp4")
        val params = currentParameters()
        val effects = mutableListOf<Effect>()
        effects.add(LmcGlEffect(params))
        val sourceSize = readVideoSize(source)
        val targetWidth = (sourceSize.first * upscale).toInt().coerceAtLeast(2)
        val targetHeight = (sourceSize.second * upscale).toInt().coerceAtLeast(2)
        val evenWidth = (targetWidth / 2) * 2
        val evenHeight = (targetHeight / 2) * 2
        effects.add(Presentation.createForWidthAndHeight(
            evenWidth.coerceAtLeast(2),
            evenHeight.coerceAtLeast(2),
            Presentation.LAYOUT_STRETCH_TO_FIT
        ))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(emptyList(), effects))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).build()
            )
            .build()

        transformer = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                    exporting = false
                    progress.progress = 100
                    status.text = "Export complete. MP4 saved in app storage: ${outFile.name}"
                    shareOutput(outFile)
                }
                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    exporting = false
                    status.text = "Export failed: ${exportException.errorCodeName}. Try 1× resolution and 14 Mbps."
                }
            }).build()
        try {
            transformer?.start(item, outFile.absolutePath)
            progress.isIndeterminate = true
            status.text = "Exporting ${upscale}× • ${bitrate / 1_000_000} Mbps. Keep app open."
        } catch (e: Exception) {
            exporting = false
            progress.isIndeterminate = false
            status.text = "Could not start export: ${e.message ?: "unknown error"}"
        }
    }

    private fun shareOutput(file: File) {
        progress.isIndeterminate = false
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(intent, "Save or share exported MP4")) }
        catch (_: Exception) { status.text = "Saved to app export folder: ${file.absolutePath}" }
    }

    private fun readXml(uri: Uri) {
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            contentResolver.openInputStream(uri).use { stream ->
                parser.setInput(stream, "UTF-8")
                var event = parser.eventType
                var currentName = ""
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> if (parser.name == "string" || parser.name == "int" || parser.name == "float") {
                            currentName = parser.getAttributeValue(null, "name")?.lowercase(Locale.ROOT) ?: ""
                        }
                        XmlPullParser.TEXT -> {
                            val value = parser.text.toFloatOrNull()
                            if (value != null) {
                                if ("saturation" in currentName) knobs["saturate"]?.value = value.coerceIn(0f, 2.2f)
                                if ("contrast" in currentName) knobs["contrast"]?.value = value.coerceIn(.6f, 1.8f)
                                if ("sharp" in currentName) knobs["sharpen"]?.value = (value * .5f).coerceIn(0f, 1.5f)
                            }
                            currentName = ""
                        }
                    }
                    event = parser.next()
                }
            }
            refreshSliderValues()
            refreshPreviewEffects()
            status.text = "LMC XML imported. Recognized saturation, contrast and sharpening values applied."
        } catch (e: Exception) {
            status.text = "Could not parse this XML: ${e.message ?: "invalid XML"}"
        }
    }

    private fun applyPreset(con: Float, sat: Float, bri: Float, warm: Float, tex: Float, shp: Float) {
        knobs["contrast"]!!.value = con
        knobs["saturate"]!!.value = sat
        knobs["brightness"]!!.value = bri
        knobs["warmth"]!!.value = warm
        knobs["texture"]!!.value = tex
        knobs["sharpen"]!!.value = shp
        knobs["clarity"]!!.value = .35f
        refreshSliderValues()
        refreshPreviewEffects()
        status.text = "Preset applied: all eight native shader controls are active."
    }

    private fun resetAll() {
        knobs["texture"]!!.value = 0f
        knobs["sharpen"]!!.value = 0f
        knobs["clarity"]!!.value = 0f
        knobs["contrast"]!!.value = 1f
        knobs["saturate"]!!.value = 1f
        knobs["brightness"]!!.value = 0f
        knobs["warmth"]!!.value = 0f
        knobs["vignette"]!!.value = 0f
        refreshSliderValues()
        refreshPreviewEffects()
        status.text = "Settings reset."
    }

    private fun currentParameters() = LmcParameters(
        contrast = knobs["contrast"]!!.value,
        saturation = knobs["saturate"]!!.value,
        brightness = knobs["brightness"]!!.value,
        warmth = knobs["warmth"]!!.value,
        vignette = knobs["vignette"]!!.value,
        texture = knobs["texture"]!!.value,
        sharpen = knobs["sharpen"]!!.value,
        clarity = knobs["clarity"]!!.value
    )

    private fun refreshSliderValues() {
        knobs.forEach { (key, knob) ->
            seekBars[key]?.progress = toProgress(knob)
            sliderLabels[key]?.text = "${knob.label}: ${String.format(Locale.US, "%.2f", knob.value)}"
        }
    }

    private fun refreshPreviewEffects() {
        val p = player ?: return
        try {
            p.setVideoEffects(if (compareOriginal) emptyList() else listOf(LmcGlEffect(currentParameters())))
        } catch (e: Exception) {
            status.text = "Preview effect unavailable for this video/device: ${e.message ?: "unsupported format"}"
        }
    }

    private fun readVideoSize(uri: Uri): Pair<Int, Int> {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
            val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
            Pair(w, h)
        } catch (_: Exception) { Pair(1280, 720) }
        finally { try { retriever.release() } catch (_: Exception) {} }
    }

    private fun updateDetails() {
        val uri = inputUri ?: return
        status.text = "Video selected • ${upscale}× export • native MP4 encoder"
    }

    private fun section(s: String) = text(s, 13, Color.rgb(56, 189, 248), true).apply {
        setPadding(0, dp(18), 0, dp(6))
    }
    private fun text(s: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size.toFloat(); setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(3))
        }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f
        setOnClickListener { action() }
    }
    private fun presetRow(a: Pair<String, () -> Unit>, b: Pair<String, () -> Unit>) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(a.first, a.second), weight())
            addView(button(b.first, b.second), weight())
        }
    private fun weight() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        setMargins(dp(2), dp(2), dp(2), dp(2))
    }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (exporting) {
            try { transformer?.cancel() } catch (_: Exception) {}
        }
        player?.release()
        super.onDestroy()
    }
}
