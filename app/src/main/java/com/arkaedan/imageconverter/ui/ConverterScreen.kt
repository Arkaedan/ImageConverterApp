package com.arkaedan.imageconverter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arkaedan.imageconverter.R
import com.arkaedan.imageconverter.conversion.ConversionOutput
import com.arkaedan.imageconverter.conversion.ImageFormat
import com.arkaedan.imageconverter.conversion.OutputSize
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Window width from which the image list and the settings sit side by side (tablets, landscape). */
private val TwoPaneMinWidth = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(viewModel: ConverterViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addImages(uris)
    }
    val pickImages = { picker.launch(arrayOf("image/*")) }
    val showMessage: (String) -> Unit = { text ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                is UserMessage.Finished ->
                    if (message.failed == 0) resources.getQuantityString(R.plurals.result_success, message.succeeded, message.succeeded)
                    else resources.getString(R.string.result_partial, message.succeeded, message.failed)
                UserMessage.Cancelled -> resources.getString(R.string.result_cancelled)
            }
            showMessage(text)
        }
    }

    val actions = ScreenActions(
        onAdd = pickImages,
        onRemove = viewModel::removeImage,
        onOpen = { output ->
            if (!context.openImage(output)) showMessage(resources.getString(R.string.no_app_to_open))
        },
        onFormat = viewModel::setFormat,
        onQuality = viewModel::setQuality,
        onLossless = viewModel::setLossless,
        onSize = viewModel::setSize,
        onConvert = viewModel::convert,
        onCancel = viewModel::cancelConversion,
        onShare = { context.shareImages(state.outputs) },
    )

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = pickImages) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_images))
                        }
                        IconButton(onClick = viewModel::clearImages, enabled = !state.isConverting) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.clear_all))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.items.isEmpty()) {
            EmptyState(onPick = pickImages, modifier = Modifier.padding(padding))
        } else {
            BoxWithConstraints(Modifier.padding(padding)) {
                if (maxWidth >= TwoPaneMinWidth) TwoPaneLayout(state, actions) else SinglePaneLayout(state, actions)
            }
        }
    }
}

private class ScreenActions(
    val onAdd: () -> Unit,
    val onRemove: (Long) -> Unit,
    val onOpen: (ConversionOutput) -> Unit,
    val onFormat: (ImageFormat) -> Unit,
    val onQuality: (Int) -> Unit,
    val onLossless: (Boolean) -> Unit,
    val onSize: (OutputSize) -> Unit,
    val onConvert: () -> Unit,
    val onCancel: () -> Unit,
    val onShare: () -> Unit,
)

@Composable
private fun EmptyState(onPick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(40.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(128.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(34.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onPick,
            contentPadding = PaddingValues(start = 20.dp, end = 24.dp),
            modifier = Modifier.height(56.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.choose_images), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SinglePaneLayout(state: ConverterUiState, actions: ScreenActions) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            imageList(state, actions)
            item(key = "settings") {
                SectionHeader(stringResource(R.string.section_output), Modifier.padding(top = 24.dp))
                SettingsCard(state, actions)
            }
        }
        ConvertBar(state, actions, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun TwoPaneLayout(state: ConverterUiState, actions: ScreenActions) {
    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            imageList(state, actions)
        }
        Column(
            Modifier
                .width(400.dp)
                .padding(end = 16.dp, bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.section_output))
            SettingsCard(state, actions)
            Spacer(Modifier.height(16.dp))
            ConvertBar(state, actions)
        }
    }
}

private fun LazyListScope.imageList(state: ConverterUiState, actions: ScreenActions) {
    item(key = "header") {
        SectionHeader(pluralStringResource(R.plurals.images_header, state.items.size, state.items.size))
    }
    itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
        ImageRow(
            item = item,
            shape = groupedShape(index, state.items.size),
            canRemove = !state.isConverting,
            onRemove = { actions.onRemove(item.id) },
            onOpen = actions.onOpen,
            modifier = Modifier
                .animateItem()
                .padding(bottom = 2.dp),
        )
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 12.dp),
    )
}

/** Large outer corners and small inner ones, so consecutive rows read as one group. */
private fun groupedShape(index: Int, count: Int): Shape {
    val outer = 24.dp
    val inner = 6.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun ImageRow(
    item: ImageItem,
    shape: Shape,
    canRemove: Boolean,
    onRemove: () -> Unit,
    onOpen: (ConversionOutput) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val output = (item.status as? ItemStatus.Done)?.output
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(enabled = output != null, onClickLabel = stringResource(R.string.open_output)) {
                    output?.let(onOpen)
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(item.info)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = sourceSummary(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = item.status
                when {
                    item.loadState == LoadState.Failed -> StatusText(
                        stringResource(R.string.error_unreadable),
                        MaterialTheme.colorScheme.error,
                        Icons.Filled.Warning,
                    )
                    status is ItemStatus.Done -> StatusText(
                        stringResource(
                            R.string.converted_to,
                            status.output.format.label,
                            Formatter.formatShortFileSize(context, status.output.sizeBytes),
                        ),
                        MaterialTheme.colorScheme.primary,
                        Icons.Filled.CheckCircle,
                    )
                    status is ItemStatus.Failed ->
                        StatusText(failureText(status.reason), MaterialTheme.colorScheme.error, Icons.Filled.Warning)
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when {
                    item.status == ItemStatus.Converting ->
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    canRemove -> IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.remove_image, item.name),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color, icon: ImageVector) {
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Thumbnail(info: ImageInfo?) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (info != null) {
            Image(info.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                painterResource(R.drawable.ic_image),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun sourceSummary(item: ImageItem): String {
    val context = LocalContext.current
    val info = item.info ?: return if (item.loadState == LoadState.Loading) stringResource(R.string.loading) else ""
    return listOfNotNull(
        info.formatLabel,
        "${info.width} × ${info.height}",
        item.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
    ).joinToString(" · ")
}

@Composable
private fun failureText(reason: FailureReason): String = when (reason) {
    FailureReason.TooLarge -> stringResource(R.string.error_too_large)
    FailureReason.Unreadable -> stringResource(R.string.error_unreadable)
    is FailureReason.Other -> stringResource(R.string.error_generic, reason.message.orEmpty())
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(state: ConverterUiState, actions: ScreenActions) {
    val settings = state.settings
    val enabled = !state.isConverting
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column {
                SettingLabel(stringResource(R.string.format))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageFormat.entries.forEach { format ->
                        val selected = format == settings.format
                        FilterChip(
                            selected = selected,
                            onClick = { actions.onFormat(format) },
                            enabled = enabled && format !in state.unavailableFormats,
                            label = { Text(format.label) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
                            } else {
                                null
                            },
                        )
                    }
                }
                SupportingText(formatDescription(settings.format))
                state.unavailableFormats.sortedBy { it.ordinal }.forEach { format ->
                    SupportingText(stringResource(R.string.format_unavailable, format.label))
                }
            }

            if (settings.usesQuality) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingLabel(stringResource(R.string.quality), Modifier.weight(1f))
                        Text(
                            text = settings.quality.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = settings.quality.toFloat(),
                        onValueChange = { actions.onQuality(it.roundToInt()) },
                        valueRange = 10f..100f,
                        steps = 17,
                        enabled = enabled,
                    )
                }
            }

            if (settings.format == ImageFormat.WEBP) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.lossless), style = MaterialTheme.typography.bodyLarge)
                        SupportingText(stringResource(R.string.lossless_summary), topPadding = 0.dp)
                    }
                    Switch(checked = settings.lossless, onCheckedChange = actions.onLossless, enabled = enabled)
                }
            }

            Column {
                SettingLabel(stringResource(R.string.size))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    OutputSize.entries.forEachIndexed { index, size ->
                        SegmentedButton(
                            selected = size == settings.size,
                            onClick = { actions.onSize(size) },
                            shape = SegmentedButtonDefaults.itemShape(index, OutputSize.entries.size),
                            enabled = enabled,
                            icon = {},
                            label = {
                                Text(
                                    text = size.longEdge?.toString() ?: stringResource(R.string.size_original),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
                SupportingText(stringResource(if (state.hasSvg) R.string.size_summary_svg else R.string.size_summary))
            }
        }
    }
}

@Composable
private fun SettingLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(bottom = 8.dp))
}

@Composable
private fun SupportingText(text: String, topPadding: androidx.compose.ui.unit.Dp = 4.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = topPadding),
    )
}

@Composable
private fun formatDescription(format: ImageFormat): String = stringResource(
    when (format) {
        ImageFormat.JPEG -> R.string.format_desc_jpeg
        ImageFormat.PNG -> R.string.format_desc_png
        ImageFormat.WEBP -> R.string.format_desc_webp
        ImageFormat.HEIF -> R.string.format_desc_heif
        ImageFormat.AVIF -> R.string.format_desc_avif
        ImageFormat.GIF -> R.string.format_desc_gif
        ImageFormat.BMP -> R.string.format_desc_bmp
    },
)

@Composable
private fun ConvertBar(state: ConverterUiState, actions: ScreenActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        val progress = state.progress
        if (progress != null) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.converting_progress, (progress.completed + 1).coerceAtMost(progress.total), progress.total),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = actions.onCancel) { Text(stringResource(R.string.cancel)) }
            }
            LinearProgressIndicator(
                progress = { progress.completed.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.outputs.isNotEmpty()) {
                    OutlinedButton(onClick = actions.onShare, modifier = Modifier.height(56.dp)) {
                        Icon(Icons.Filled.Share, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(stringResource(R.string.share))
                    }
                }
                val pending = state.pendingCount
                Button(
                    onClick = actions.onConvert,
                    enabled = pending > 0 && !state.isLoading,
                    modifier = Modifier.weight(1f).height(56.dp),
                ) {
                    Text(
                        text = if (pending == 0 && state.outputs.isNotEmpty()) stringResource(R.string.all_converted)
                        else pluralStringResource(R.plurals.convert_images, pending, pending),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

private fun Context.openImage(output: ConversionOutput): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(output.uri, output.format.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun Context.shareImages(outputs: List<ConversionOutput>) {
    if (outputs.isEmpty()) return
    val mimeType = outputs.map { it.format.mimeType }.distinct().singleOrNull() ?: "image/*"
    val intent = if (outputs.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, outputs.single().uri)
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(outputs.map { it.uri }))
    }
    intent.setType(mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)))
}
