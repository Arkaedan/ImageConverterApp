package com.arkaedan.imageconverter.ui

import android.app.Application
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arkaedan.imageconverter.conversion.ConversionOutput
import com.arkaedan.imageconverter.conversion.ConversionSettings
import com.arkaedan.imageconverter.conversion.EncoderSupport
import com.arkaedan.imageconverter.conversion.ImageConverter
import com.arkaedan.imageconverter.conversion.ImageFormat
import com.arkaedan.imageconverter.conversion.ImageLoader
import com.arkaedan.imageconverter.conversion.OutputSize
import com.caverock.androidsvg.SVGParseException
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImageInfo(
    val width: Int,
    val height: Int,
    val formatLabel: String?,
    val thumbnail: ImageBitmap,
)

enum class LoadState { Loading, Loaded, Failed }

sealed interface FailureReason {
    data object TooLarge : FailureReason
    data object Unreadable : FailureReason
    data class Other(val message: String?) : FailureReason
}

sealed interface ItemStatus {
    data object Idle : ItemStatus
    data object Queued : ItemStatus
    data object Converting : ItemStatus
    data class Done(val output: ConversionOutput) : ItemStatus
    data class Failed(val reason: FailureReason) : ItemStatus
}

data class ImageItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long? = null,
    val isSvg: Boolean = false,
    val info: ImageInfo? = null,
    val loadState: LoadState = LoadState.Loading,
    val status: ItemStatus = ItemStatus.Idle,
)

data class Progress(val completed: Int, val total: Int)

data class ConverterUiState(
    val items: List<ImageItem> = emptyList(),
    val settings: ConversionSettings = ConversionSettings(),
    val unavailableFormats: Set<ImageFormat> = emptySet(),
    /** Non-null while a conversion is running. */
    val progress: Progress? = null,
) {
    val isConverting get() = progress != null
    val isLoading get() = items.any { it.loadState == LoadState.Loading }
    val convertibleCount get() = items.count { it.loadState == LoadState.Loaded }
    val hasSvg get() = items.any { it.isSvg }
    val outputs get() = items.mapNotNull { (it.status as? ItemStatus.Done)?.output }
}

sealed interface UserMessage {
    data class Finished(val succeeded: Int, val failed: Int) : UserMessage
    data object Cancelled : UserMessage
}

class ConverterViewModel(application: Application) : AndroidViewModel(application) {
    private val loader = ImageLoader(application.contentResolver)
    private val converter = ImageConverter(application)
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val probeDispatcher = Dispatchers.IO.limitedParallelism(3)

    private val _uiState = MutableStateFlow(ConverterUiState(settings = loadSettings()))
    val uiState: StateFlow<ConverterUiState> = _uiState.asStateFlow()

    private val _messages = Channel<UserMessage>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var nextId = 0L
    private var conversionJob: Job? = null

    init {
        viewModelScope.launch {
            // Querying the codec list takes a moment, so do it off the main thread.
            val unavailable = withContext(Dispatchers.Default) {
                ImageFormat.entries.filterNot(EncoderSupport::isSupported).toSet()
            }
            _uiState.update { state ->
                val settings = if (state.settings.format in unavailable) state.settings.copy(format = ImageFormat.JPEG) else state.settings
                state.copy(unavailableFormats = unavailable, settings = settings)
            }
        }
    }

    fun addImages(uris: List<Uri>) {
        val existing = _uiState.value.items.mapTo(HashSet()) { it.uri }
        val added = uris.distinct().filterNot { it in existing }.map { uri ->
            ImageItem(id = nextId++, uri = uri, name = uri.lastPathSegment?.substringAfterLast('/') ?: "image")
        }
        if (added.isEmpty()) return
        _uiState.update { it.copy(items = it.items + added) }
        added.forEach { item -> viewModelScope.launch(probeDispatcher) { probe(item) } }
    }

    private fun probe(item: ImageItem) {
        val name = runCatching { loader.displayName(item.uri) }.getOrNull() ?: item.name
        val size = runCatching { loader.fileSize(item.uri) }.getOrNull()
        val isSvg = runCatching { loader.isSvg(item.uri, name) }.getOrDefault(false)
        val info = try {
            loader.details(item.uri, isSvg, THUMBNAIL_SIZE).let { details ->
                ImageInfo(details.width, details.height, details.formatLabel, details.thumbnail.asImageBitmap())
            }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
        updateItem(item.id) {
            it.copy(
                name = name,
                sizeBytes = size,
                isSvg = isSvg,
                info = info,
                loadState = if (info != null) LoadState.Loaded else LoadState.Failed,
            )
        }
    }

    fun removeImage(id: Long) {
        if (_uiState.value.isConverting) return
        _uiState.update { state -> state.copy(items = state.items.filterNot { it.id == id }) }
    }

    fun clearImages() {
        if (_uiState.value.isConverting) return
        _uiState.update { it.copy(items = emptyList()) }
    }

    fun setFormat(format: ImageFormat) = updateSettings { it.copy(format = format) }
    fun setQuality(quality: Int) = updateSettings { it.copy(quality = quality) }
    fun setLossless(lossless: Boolean) = updateSettings { it.copy(lossless = lossless) }
    fun setSize(size: OutputSize) = updateSettings { it.copy(size = size) }

    fun convert() {
        if (conversionJob?.isActive == true) return
        val state = _uiState.value
        val targets = state.items.filter { it.loadState == LoadState.Loaded }
        if (targets.isEmpty()) return
        val settings = state.settings
        val targetIds = targets.mapTo(HashSet()) { it.id }

        _uiState.update { current ->
            current.copy(
                items = current.items.map { if (it.id in targetIds) it.copy(status = ItemStatus.Queued) else it },
                progress = Progress(0, targets.size),
            )
        }
        conversionJob = viewModelScope.launch {
            var succeeded = 0
            var failed = 0
            for (item in targets) {
                // Cancelling stops before the next image; the current one always finishes cleanly.
                if (!isActive) break
                updateItem(item.id) { it.copy(status = ItemStatus.Converting) }
                val status = withContext(Dispatchers.Default + NonCancellable) {
                    try {
                        ItemStatus.Done(converter.convert(item.uri, item.name, item.isSvg, settings))
                    } catch (e: OutOfMemoryError) {
                        ItemStatus.Failed(FailureReason.TooLarge)
                    } catch (e: Exception) {
                        ItemStatus.Failed(e.toFailureReason())
                    }
                }
                if (status is ItemStatus.Done) succeeded++ else failed++
                updateItem(item.id) { it.copy(status = status) }
                _uiState.update { it.copy(progress = it.progress?.copy(completed = succeeded + failed)) }
            }
            val cancelled = !isActive
            _uiState.update { current ->
                current.copy(
                    items = current.items.map { if (it.status == ItemStatus.Queued) it.copy(status = ItemStatus.Idle) else it },
                    progress = null,
                )
            }
            _messages.trySend(if (cancelled) UserMessage.Cancelled else UserMessage.Finished(succeeded, failed))
        }
    }

    fun cancelConversion() {
        conversionJob?.cancel()
    }

    private fun Exception.toFailureReason(): FailureReason = when (this) {
        is ImageDecoder.DecodeException, is SVGParseException, is FileNotFoundException, is SecurityException ->
            FailureReason.Unreadable
        else -> FailureReason.Other(message ?: javaClass.simpleName)
    }

    private fun updateItem(id: Long, transform: (ImageItem) -> ImageItem) {
        _uiState.update { state -> state.copy(items = state.items.map { if (it.id == id) transform(it) else it }) }
    }

    private fun updateSettings(transform: (ConversionSettings) -> ConversionSettings) {
        _uiState.update { it.copy(settings = transform(it.settings)) }
        saveSettings(_uiState.value.settings)
    }

    private fun loadSettings(): ConversionSettings {
        val defaults = ConversionSettings()
        return ConversionSettings(
            format = prefs.getString(KEY_FORMAT, null)?.let { name -> ImageFormat.entries.find { it.name == name } } ?: defaults.format,
            quality = prefs.getInt(KEY_QUALITY, defaults.quality),
            lossless = prefs.getBoolean(KEY_LOSSLESS, defaults.lossless),
            size = prefs.getString(KEY_SIZE, null)?.let { name -> OutputSize.entries.find { it.name == name } } ?: defaults.size,
        )
    }

    private fun saveSettings(settings: ConversionSettings) = prefs.edit {
        putString(KEY_FORMAT, settings.format.name)
        putInt(KEY_QUALITY, settings.quality)
        putBoolean(KEY_LOSSLESS, settings.lossless)
        putString(KEY_SIZE, settings.size.name)
    }

    private companion object {
        const val THUMBNAIL_SIZE = 256
        const val KEY_FORMAT = "format"
        const val KEY_QUALITY = "quality"
        const val KEY_LOSSLESS = "lossless"
        const val KEY_SIZE = "size"
    }
}
